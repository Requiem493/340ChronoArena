package game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GameState - Authoritative server-side game state.
 *
 * Freeze, speed boost, and weapon handling removed to match GameClient.
 * Only MOVE action and ENERGY item remain.
 *
 * @author Requiem493, help from claude.ai
 */
public class GameState {

    // Map dimensions — must match ZoneData.CENTRES in GameClient
    public static final int MAP_WIDTH = 800;
    public static final int MAP_HEIGHT = 600;
    private static final int MOVE_SPEED = 5;
    private static final int FREEZE_RANGE = 90;
    private static final long FREEZE_DURATION_MS = 3000;

    // Timing
    private final long roundDurationMs;
    private long roundStartTime;
    private volatile boolean running = false;

    // Players: playerID -> PlayerState
    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();
    private int nextPlayerId = 1;

    // Zones
    private final List<Zone> zones;

    // Items
    private static final int MAX_ITEMS_ON_MAP = 8;
    private static final long MIN_ITEM_SPAWN_MS = 4000;
    private static final long MAX_ITEM_SPAWN_MS = 6000;
    private final List<Item> items = Collections.synchronizedList(new ArrayList<>());
    private final Random rng = new Random();
    private long nextItemSpawnInMs = randomSpawnDelayMs();

    // Action queue — UDP thread enqueues, GameLoop drains each tick
    private final ConcurrentLinkedQueue<String> actionQueue = new ConcurrentLinkedQueue<>();

    // Per-player sequence numbers for UDP dedup / out-of-order drops
    private final Map<String, Long> lastSeqByPlayer = new ConcurrentHashMap<>();

    public GameState(long roundDurationMs) {
        this.roundDurationMs = roundDurationMs;
        this.zones = buildDefaultZones();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLAYER MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════════

    /** Called on TCP JOIN. Returns the assigned playerID. */
    public synchronized String addPlayer(String name) {
        String id = "P" + nextPlayerId++;
        int startX = 50 + rng.nextInt(MAP_WIDTH - 100);
        int startY = 50 + rng.nextInt(MAP_HEIGHT - 100);
        players.put(id, new PlayerState(id, name, startX, startY));
        System.out.println("[GameState] Player joined: " + name + " -> " + id);
        return id;
    }

    /** Called on disconnect. Releases any zone the player owned. */
    public synchronized void removePlayer(String playerId) {
        players.remove(playerId);
        lastSeqByPlayer.remove(playerId);
        for (Zone z : zones) {
            z.onPlayerLeft(playerId);
        }
        System.out.println("[GameState] Player removed: " + playerId);
    }

    public PlayerState getPlayer(String id) {
        return players.get(id);
    }

    public Collection<PlayerState> allPlayers() {
        return players.values();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACTION QUEUE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called by UDPActionReceiver for every incoming packet.
     * Expected format: "<seq>|<playerID>|<ACTION>|<params...>"
     * Drops duplicate and out-of-order packets using per-player seq numbers.
     */
    public void enqueueAction(String raw) {
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 3)
            return;

        long seq = Long.parseLong(parts[0]);
        String pid = parts[1];

        // Drop stale / duplicate
        long lastSeen = lastSeqByPlayer.getOrDefault(pid, -1L);
        if (seq <= lastSeen) {
            System.out.println("[GameState] Dropped stale packet seq=" + seq + " pid=" + pid);
            return;
        }
        lastSeqByPlayer.put(pid, seq);

        // Strip seq number before queuing
        String action = parts[2];
        String params = parts.length > 3 ? parts[3] : "";
        actionQueue.offer(pid + "|" + action + "|" + params);
    }

    /** GameLoop calls this each tick to get all pending actions. */
    public List<String> drainActions() {
        List<String> batch = new ArrayList<>();
        String a;
        while ((a = actionQueue.poll()) != null)
            batch.add(a);
        return batch;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAME LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    public void startRound() {
        roundStartTime = System.currentTimeMillis();
        running = true;
        spawnItems(5);
        nextItemSpawnInMs = randomSpawnDelayMs();
        System.out.println("[GameState] Round started. Duration: "
                + (roundDurationMs / 1000) + "s");
    }

    public void endRound() {
        running = false;
        System.out.println("[GameState] Round ended.");
    }

    public boolean isRunning() {
        return running;
    }

    public long timeRemainingMs() {
        if (!running)
            return 0;
        return Math.max(0, roundDurationMs - (System.currentTimeMillis() - roundStartTime));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAME LOGIC UPDATES (called by GameLoop each tick)
    // ══════════════════════════════════════════════════════════════════════════

    /** Process one action string from the drained batch. */
    public void processAction(String action) {
        String[] parts = action.split("\\|", 3);
        if (parts.length < 2)
            return;

        String pid = parts[0];
        String type = parts[1];
        String args = parts.length > 2 ? parts[2] : "";

        PlayerState p = players.get(pid);
        if (p == null)
            return;

        switch (type) {
            case "MOVE" -> handleMove(p, args);
            case "FREEZE" -> handleFreeze(p);
            default -> System.out.println("[GameState] Unknown action: " + type);
        }
    }

    /** Tick zone capture/grace logic and award zone points. */
    public void tickZones(long deltaMs) {
        for (Zone z : zones) {
            z.tick(deltaMs, players);
        }
    }

    public void tickStatusEffects() {
        long now = System.currentTimeMillis();
        for (PlayerState p : players.values()) {
            if (p.frozen && now >= p.frozenUntilMs) {
                p.frozen = false;
                p.frozenUntilMs = 0;
            }
        }
    }

    /** Spawn one new item every 4-6 seconds, up to the map cap. */
    public void maybeSpawnItem(long tickMs) {
        nextItemSpawnInMs -= tickMs;
        if (nextItemSpawnInMs > 0) {
            return;
        }

        if (items.size() < MAX_ITEMS_ON_MAP) {
            spawnItems(1);
            nextItemSpawnInMs = randomSpawnDelayMs();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACTION HANDLERS
    // ══════════════════════════════════════════════════════════════════════════

    private void handleMove(PlayerState p, String args) {
        // args = "dx|dy" (each -1, 0, or 1)
        String[] xy = args.split("\\|");
        if (xy.length < 2)
            return;

        if (p.frozen)
            return;

        int dx = Integer.parseInt(xy[0]);
        int dy = Integer.parseInt(xy[1]);

        int nx = Math.max(0, Math.min(MAP_WIDTH - 1, p.x + dx * MOVE_SPEED));
        int ny = Math.max(0, Math.min(MAP_HEIGHT - 1, p.y + dy * MOVE_SPEED));
        p.x = nx;
        p.y = ny;

        // Check zone entry/exit
        for (Zone z : zones) {
            z.onPlayerMoved(p, players);
        }

        // Check item pickup
        List<Item> toRemove = new ArrayList<>();
        for (Item item : items) {
            if (item.overlaps(p.x, p.y)) {
                applyItem(p, item);
                toRemove.add(item);
            }
        }
        items.removeAll(toRemove);
        }

    private void applyItem(PlayerState p, Item item) {
        // Only ENERGY exists — +15 pts
        if (item.type == Item.Type.ENERGY) {
            p.score += 15;
        } else if (item.type == Item.Type.FREEZE_RAY) {
            p.hasWeapon = true;
        }
    }

    private void handleFreeze(PlayerState attacker) {
        if (attacker.frozen || !attacker.hasWeapon) {
            return;
        }

        PlayerState target = findNearestTarget(attacker);
        if (target == null) {
            return;
        }

        target.frozen = true;
        target.frozenUntilMs = System.currentTimeMillis() + FREEZE_DURATION_MS;
        attacker.hasWeapon = false;
        System.out.println("[GameState] " + attacker.name + " froze " + target.name);
    }

    private PlayerState findNearestTarget(PlayerState attacker) {
        PlayerState nearest = null;
        double nearestDistance = FREEZE_RANGE + 1.0;

        for (PlayerState candidate : players.values()) {
            if (candidate == attacker || candidate.killed) {
                continue;
            }

            double distance = Math.hypot(candidate.x - attacker.x, candidate.y - attacker.y);
            if (distance <= FREEZE_RANGE && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    public synchronized String serializeLiveState() {
        StringBuilder sb = new StringBuilder("STATE|");
        sb.append(timeRemainingMs()).append("|PLAYERS:");

        boolean first = true;
        for (PlayerState p : players.values()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(p.id).append(':')
                    .append(p.name).append(':')
                    .append(p.x).append(':')
                    .append(p.y).append(':')
                    .append(p.score).append(':')
                    .append(p.frozen ? 1 : 0).append(':')
                    .append(p.hasWeapon ? 1 : 0).append(':')
                    .append(0);
            first = false;
        }

        sb.append("|ZONES:");
        first = true;
        for (Zone z : zones) {
            if (!first) {
                sb.append(',');
            }
            sb.append(z.serialize());
            first = false;
        }

        sb.append("|ITEMS:");
        first = true;
        for (Item item : items) {
            if (!first) {
                sb.append(',');
            }
            sb.append(item.id).append(':')
                    .append(item.type).append(':')
                    .append(item.x).append(':')
                    .append(item.y);
            first = false;
        }

        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SERIALISATION
    //
    // Format sent over TCP every tick:
    // STATE|<timeMs>|PLAYERS:<p,...>|ZONES:<z,...>|ITEMS:<i,...>
    //
    // Player entry : id:name:x:y:score:0:0:0
    // (frozen/hasWeapon/speedBoost fields kept as 0 so the client parser
    // still works without modification — f[5], f[6], f[7] are read but
    // the relevant ones are commented out in GameClient)
    // Zone entry : id:owner:status:capturePercent
    // Item entry : id:type:x:y
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder("STATE|");
        sb.append(timeRemainingMs()).append("|PLAYERS:");

        boolean first = true;
        for (PlayerState p : players.values()) {
            if (!first)
                sb.append(',');
            sb.append(p.id).append(':')
                    .append(p.name).append(':')
                    .append(p.x).append(':')
                    .append(p.y).append(':')
                    .append(p.score).append(':')
                    .append(0).append(':') // frozen — always 0
                    .append(0).append(':') // hasWeapon — always 0
                    .append(0); // speedBoost — always 0
            first = false;
        }

        sb.append("|ZONES:");
        first = true;
        for (Zone z : zones) {
            if (!first)
                sb.append(',');
            sb.append(z.serialize());
            first = false;
        }

        sb.append("|ITEMS:");
        first = true;
        for (Item item : items) {
            if (!first)
                sb.append(',');
            sb.append(item.id).append(':')
                    .append(item.type).append(':')
                    .append(item.x).append(':')
                    .append(item.y);
            first = false;
        }

        return sb.toString();
    }

    /** Final leaderboard — sent once when round ends. */
    public synchronized String serializeFinalScores() {
        List<PlayerState> sorted = new ArrayList<>(players.values());
        sorted.sort((a, b) -> Integer.compare(b.score, a.score));

        StringBuilder sb = new StringBuilder("FINAL|");
        for (int i = 0; i < sorted.size(); i++) {
            PlayerState p = sorted.get(i);
            sb.append(p.name).append(':').append(p.score);
            if (i < sorted.size() - 1)
                sb.append(',');
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KILL SWITCH
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void killPlayer(String playerId) {
        PlayerState p = players.get(playerId);
        if (p != null) {
            p.killed = true;
            System.out.println("[KILL_SWITCH] Killed player: " + playerId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private List<Zone> buildDefaultZones() {
        List<Zone> list = new ArrayList<>();
        list.add(new Zone("ZA", 150, 150, 100, 80));
        list.add(new Zone("ZB", 550, 150, 100, 80));
        list.add(new Zone("ZC", 350, 350, 100, 80));
        list.add(new Zone("ZD", 150, 450, 100, 80));
        list.add(new Zone("ZE", 550, 450, 100, 80));
        return list;
    }

    private void spawnItems(int count) {
        int itemsToSpawn = Math.min(count, MAX_ITEMS_ON_MAP - items.size());
        for (int i = 0; i < itemsToSpawn; i++) {
            int x = 20 + rng.nextInt(MAP_WIDTH - 40);
            int y = 20 + rng.nextInt(MAP_HEIGHT - 40);
            Item.Type type = rng.nextInt(4) == 0 ? Item.Type.FREEZE_RAY : Item.Type.ENERGY;
            items.add(new Item(type, x, y));
        }
    }

    private long randomSpawnDelayMs() {
        return MIN_ITEM_SPAWN_MS
                + rng.nextInt((int) (MAX_ITEM_SPAWN_MS - MIN_ITEM_SPAWN_MS + 1));
    }

    public List<Zone> getZones() {
        return zones;
    }

    public List<Item> getItems() {
        return items;
    }
}
