package game;

import java.util.*;
import java.util.Locale;

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

    public static final long CAPTURE_TIME_MS = 7_000; // 7s to capture
    public static final long OWNERSHIP_DURATION_MS = 7_000; // 7s of control after capture
    public static final long GRACE_PERIOD_MS = 5_000; // 5s grace when owner leaves
    private static final long CAPTURE_DECAY_HALF_LIFE_MS = 2_000; // interrupted progress halves every 2s
    private static final long OWNERSHIP_SCORE_HALF_LIFE_MS = 2_000; // owned-zone score halves every 2s
    private static final long MIN_CAPTURE_PROGRESS_MS = 25; // tiny remnants are treated as zero
    public static final int POINTS_PER_TICK = 2; // points per game-loop tick
    public static final int ZONE_RADIUS = 50; // pixel radius for overlap
    private static final double BASE_POINTS_PER_MS = (double) POINTS_PER_TICK / GameLoop.TICK_RATE_MS;

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
    private long ownershipRemainingMs = 0;
    private double pendingOwnedScore = 0.0;

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

        refreshStatus();
    }

    /** Called when a player disconnects. */
    public synchronized void onPlayerLeft(String pid) {
        playersInside.remove(pid);
        if (pid.equals(owner)) {
            clearOwnership();
            status = Status.UNCLAIMED;
        }
        if (pid.equals(capturingPlayer)) {
            capturingPlayer = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK (called by GameState.tickZones every game-loop iteration)
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void tick(long deltaMs, Map<String, PlayerState> allPlayers) {
        long now = System.currentTimeMillis();

        if (owner != null && ownershipRemainingMs > 0) {
            ownershipRemainingMs = Math.max(0, ownershipRemainingMs - deltaMs);
            if (ownershipRemainingMs == 0) {
                System.out.println("[Zone " + id + "] Ownership timer expired for " + owner);
                expireOwnership();
                return;
            }
        }

        switch (status) {

            case CAPTURED -> {
                if (owner == null) {
                    status = Status.UNCLAIMED;
                    break;
                }
                // Owner is inside → award points
                if (playersInside.containsKey(owner)) {
                    PlayerState ownerState = allPlayers.get(owner);
                    if (ownerState != null) {
                        awardOwnedPoints(ownerState, deltaMs);
                    }
                    graceStart = -1; // reset grace since they're here
                } else {
                    // Owner left — start or continue grace period
                    if (graceStart < 0) {
                        graceStart = now;
                        System.out.println("[Zone " + id + "] Grace period started for " + owner);
                    } else if (now - graceStart > GRACE_PERIOD_MS) {
                        System.out.println("[Zone " + id + "] Grace expired — zone lost by " + owner);
                        expireOwnership();
                    }
                }
            }

            case CAPTURING -> {
                if (playersInside.size() > 1) {
                    status = Status.CONTESTED;
                } else if (playersInside.size() == 1) {
                    String sole = getSoleOccupant();
                    if (capturingPlayer == null || !capturingPlayer.equals(sole)) {
                        capturingPlayer = sole;
                    }
                    captureProgress += deltaMs;
                    if (captureProgress >= CAPTURE_TIME_MS) {
                        owner = capturingPlayer;
                        status = Status.CAPTURED;
                        captureProgress = 0;
                        capturingPlayer = null;
                        graceStart = -1;
                        ownershipRemainingMs = OWNERSHIP_DURATION_MS;
                        pendingOwnedScore = 0.0;
                        System.out.println("[Zone " + id + "] Captured by " + owner);
                    }
                } else {
                    decayCaptureProgress(deltaMs);
                    capturingPlayer = null;
                    status = Status.UNCLAIMED;
                }
            }

            case CONTESTED -> {
                decayCaptureProgress(deltaMs);
                if (playersInside.size() == 1) {
                    String sole = getSoleOccupant();
                    if (owner != null && owner.equals(sole)) {
                        status = Status.CAPTURED;
                        capturingPlayer = null;
                        graceStart = -1;
                        System.out.println("[Zone " + id + "] Contest resolved - owner retained control");
                    } else {
                        capturingPlayer = sole;
                        status = Status.CAPTURING;
                        System.out.println("[Zone " + id + "] Contest resolved - " + sole + " capturing");
                    }
                } else if (playersInside.isEmpty()) {
                    status = (owner != null) ? Status.CAPTURED : Status.UNCLAIMED;
                    capturingPlayer = null;
                }
            }

            case UNCLAIMED -> {
                if (playersInside.size() == 1) {
                    capturingPlayer = playersInside.keySet().iterator().next();
                    status = Status.CAPTURING;
                    System.out.println("[Zone " + id + "] " + capturingPlayer + " started capturing");
                } else if (playersInside.size() > 1) {
                    decayCaptureProgress(deltaMs);
                    status = Status.CONTESTED;
                } else {
                    decayCaptureProgress(deltaMs);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void refreshStatus() {
        int inside = playersInside.size();

        if (status == Status.CAPTURED && inside > 1) {
            // Challenger entered an owned zone → contested
            status = Status.CONTESTED;
            System.out.println("[Zone " + id + "] Contested!");
        } else if (status == Status.CAPTURING && inside > 1) {
            status = Status.CONTESTED;
        }
    }

    private void decayCaptureProgress(long deltaMs) {
        if (captureProgress <= 0) {
            return;
        }

        double decayFactor = Math.pow(0.5, (double) deltaMs / CAPTURE_DECAY_HALF_LIFE_MS);
        captureProgress = Math.round(captureProgress * decayFactor);
        if (captureProgress < MIN_CAPTURE_PROGRESS_MS) {
            captureProgress = 0;
        }
    }

    private void awardOwnedPoints(PlayerState ownerState, long deltaMs) {
        long elapsedOwnershipMs = OWNERSHIP_DURATION_MS - ownershipRemainingMs;
        double scoreMultiplier = Math.pow(0.5, (double) elapsedOwnershipMs / OWNERSHIP_SCORE_HALF_LIFE_MS);
        pendingOwnedScore += BASE_POINTS_PER_MS * deltaMs * scoreMultiplier;

        int wholePoints = (int) pendingOwnedScore;
        if (wholePoints > 0) {
            ownerState.score += wholePoints;
            pendingOwnedScore -= wholePoints;
        }
    }

    private void expireOwnership() {
        clearOwnership();
        captureProgress = 0;

        if (playersInside.isEmpty()) {
            status = Status.UNCLAIMED;
            capturingPlayer = null;
        } else if (playersInside.size() == 1) {
            capturingPlayer = getSoleOccupant();
            status = Status.CAPTURING;
            System.out.println("[Zone " + id + "] " + capturingPlayer + " started capturing");
        } else {
            capturingPlayer = null;
            status = Status.CONTESTED;
        }
    }

    private void clearOwnership() {
        owner = null;
        graceStart = -1;
        ownershipRemainingMs = 0;
        pendingOwnedScore = 0.0;
    }

    private String getSoleOccupant() {
        if (playersInside.size() != 1) {
            return null;
        }
        return playersInside.keySet().iterator().next();
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

    public double controlPercent() {
        if (owner != null && ownershipRemainingMs > 0) {
            return Math.min(1.0, (double) ownershipRemainingMs / OWNERSHIP_DURATION_MS);
        }
        return capturePercent();
    }

    /** Serialise for the STATE broadcast. Format: id:owner:status:capturePercent */
    public String serialize() {
        String ownerStr = (owner != null) ? owner : "NONE";
        return id + ":" + ownerStr + ":" + status.name() + ":" + String.format(Locale.US, "%.2f", controlPercent());
    }
}
