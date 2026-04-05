package game;

import java.util.*;

/**
 * Zone - A capturable control zone on the map.
 *
 * Capture rules (fairness design):
 * 1. A player must stand inside the zone for CAPTURE_TIME_MS continuously.
 * 2. If a second player enters while one is capturing → CONTESTED.
 * Both capture timers pause until only one player remains.
 * 3. Once captured, the owner earns POINTS_PER_TICK every tick.
 * 4. If the owner leaves, a GRACE_PERIOD_MS timer starts.
 * If they return before it expires → ownership kept.
 * If not → zone reverts to UNCLAIMED.
 * 5. Contested resolution: when contest clears, the player who was inside
 * *first* (by entry timestamp) gets priority; other resets their timer.
 *
 * @author ChronoArena Team
 */
public class Zone {

    public static final long CAPTURE_TIME_MS = 3_000; // 3s to capture
    public static final long GRACE_PERIOD_MS = 5_000; // 5s grace when owner leaves
    public static final int POINTS_PER_TICK = 2; // points per game-loop tick
    public static final int ZONE_RADIUS = 50; // pixel radius for overlap

    public enum Status {
        UNCLAIMED, CAPTURING, CAPTURED, CONTESTED
    }

    // Identity / geometry
    public final String id;
    public final int cx, cy; // centre
    public final int w, h; // bounding box (for rendering)

    // Ownership
    public String owner = null; // playerID of current owner
    public Status status = Status.UNCLAIMED;

    // Capture progress (0 → CAPTURE_TIME_MS)
    private String capturingPlayer = null;
    private long captureProgress = 0;

    // Grace timer
    private long graceStart = -1;

    // Players currently inside this zone: playerID → entry timestamp
    private final Map<String, Long> playersInside = new LinkedHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    public Zone(String id, int cx, int cy, int w, int h) {
        this.id = id;
        this.cx = cx;
        this.cy = cy;
        this.w = w;
        this.h = h;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MOVEMENT CALLBACKS (called by GameState.handleMove)
    // ══════════════════════════════════════════════════════════════════════════

    /** Called whenever any player moves. Updates who is inside the zone. */
    public synchronized void onPlayerMoved(PlayerState p, Map<String, PlayerState> allPlayers) {
        boolean inside = contains(p.x, p.y);
        boolean wasInside = playersInside.containsKey(p.id);

        if (inside && !wasInside) {
            playersInside.put(p.id, System.currentTimeMillis());
            System.out.println("[Zone " + id + "] " + p.name + " entered");
        } else if (!inside && wasInside) {
            playersInside.remove(p.id);
            System.out.println("[Zone " + id + "] " + p.name + " left");
        }

        refreshStatus(p.id);
    }

    /** Called when a player disconnects. */
    public synchronized void onPlayerLeft(String pid) {
        playersInside.remove(pid);
        if (pid.equals(owner)) {
            owner = null;
            status = Status.UNCLAIMED;
            graceStart = -1;
        }
        if (pid.equals(capturingPlayer)) {
            capturingPlayer = null;
            captureProgress = 0;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK (called by GameState.tickZones every game-loop iteration)
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void tick(long deltaMs, Map<String, PlayerState> allPlayers) {
        long now = System.currentTimeMillis();

        switch (status) {

            case CAPTURED -> {
                // Owner is inside → award points
                if (playersInside.containsKey(owner)) {
                    PlayerState ownerState = allPlayers.get(owner);
                    if (ownerState != null)
                        ownerState.score += POINTS_PER_TICK;
                    graceStart = -1; // reset grace since they're here
                } else {
                    // Owner left — start or continue grace period
                    if (graceStart < 0) {
                        graceStart = now;
                        System.out.println("[Zone " + id + "] Grace period started for " + owner);
                    } else if (now - graceStart > GRACE_PERIOD_MS) {
                        System.out.println("[Zone " + id + "] Grace expired — zone lost by " + owner);
                        owner = null;
                        status = Status.UNCLAIMED;
                        graceStart = -1;
                    }
                }
            }

            case CAPTURING -> {
                if (capturingPlayer != null && playersInside.containsKey(capturingPlayer)) {
                    captureProgress += deltaMs;
                    if (captureProgress >= CAPTURE_TIME_MS) {
                        // Capture complete!
                        owner = capturingPlayer;
                        status = Status.CAPTURED;
                        captureProgress = 0;
                        capturingPlayer = null;
                        System.out.println("[Zone " + id + "] Captured by " + owner);
                    }
                } else {
                    // Capturing player left mid-capture
                    capturingPlayer = null;
                    captureProgress = 0;
                    status = Status.UNCLAIMED;
                }
            }

            case CONTESTED -> {
                // Contested: if only one player remains, they start/resume capturing
                if (playersInside.size() == 1) {
                    String sole = playersInside.keySet().iterator().next();
                    capturingPlayer = sole;
                    captureProgress = 0; // reset — fairness rule
                    status = Status.CAPTURING;
                    System.out.println("[Zone " + id + "] Contest resolved → " + sole + " capturing");
                } else if (playersInside.isEmpty()) {
                    status = Status.UNCLAIMED;
                    capturingPlayer = null;
                    captureProgress = 0;
                }
            }

            case UNCLAIMED -> {
                if (playersInside.size() == 1) {
                    capturingPlayer = playersInside.keySet().iterator().next();
                    captureProgress = 0;
                    status = Status.CAPTURING;
                    System.out.println("[Zone " + id + "] " + capturingPlayer + " started capturing");
                } else if (playersInside.size() > 1) {
                    status = Status.CONTESTED;
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void refreshStatus(String movedPlayer) {
        int inside = playersInside.size();

        if (status == Status.CAPTURED && inside > 1) {
            // Challenger entered an owned zone → contested
            status = Status.CONTESTED;
            System.out.println("[Zone " + id + "] Contested!");
        } else if (status == Status.CAPTURING && inside > 1) {
            status = Status.CONTESTED;
            captureProgress = 0;
        }
    }

    public boolean contains(int px, int py) {
        // Use circular check around centre
        double dx = px - cx;
        double dy = py - cy;
        return Math.hypot(dx, dy) <= ZONE_RADIUS;
    }

    /** Capture progress 0.0–1.0 (useful for rendering a progress bar). */
    public double capturePercent() {
        return Math.min(1.0, (double) captureProgress / CAPTURE_TIME_MS);
    }

    /** Serialise for the STATE broadcast. Format: id:owner:status:capturePercent */
    public String serialize() {
        String ownerStr = (owner != null) ? owner : "NONE";
        return id + ":" + ownerStr + ":" + status.name() + ":" + String.format("%.2f", capturePercent());
    }
}
