package game;

import java.util.*;
import java.util.concurrent.*;

/**
 * GameState - Authoritative server-side game state.
 *
 * Authoritative game-state and rules for ChronoArena.
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
    private volatile boolean roundStarted = false;
    private volatile boolean running = false;

    // Players: playerID -> PlayerState
    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();
    private int nextPlayerId = 1;

    // Zones
    private final List<Zone> zones;

    // Items
    private static final int MAX_ITEMS_ON_MAP = 8;
    private static final long MIN_ENERGY_SPAWN_MS = 4000;
    private static final long MAX_ENERGY_SPAWN_MS = 6000;
    private static final long FREEZE_RAY_SPAWN_MS = 10_000;
    private static final int MAX_FREEZE_RAYS_ON_MAP = 2;
    private final List<Item> items = new CopyOnWriteArrayList<>();
    private final Random rng = new Random();
    private long nextEnergySpawnInMs = randomEnergySpawnDelayMs();
    private long nextFreezeRaySpawnInMs = FREEZE_RAY_SPAWN_MS;

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

    public synchronized void startRound() {
        if (roundStarted) {
            return;
        }

        roundStarted = true;
        roundStartTime = System.currentTimeMillis();
        running = true;
        spawnItems(5);
        nextEnergySpawnInMs = randomEnergySpawnDelayMs();
        nextFreezeRaySpawnInMs = FREEZE_RAY_SPAWN_MS;
        System.out.println("[GameState] Round started. Duration: "
                + (roundDurationMs / 1000) + "s");
    }

    public synchronized void endRound() {
        if (!roundStarted || !running) {
            return;
        }

        running = false;
        System.out.println("[GameState] Round ended.");
    }

    public boolean isRunning() {
        return running;
    }

    public boolean hasRoundStarted() {
        return roundStarted;
    }

    public boolean hasRoundEnded() {
        return roundStarted && !running;
    }

    public long timeRemainingMs() {
        if (!roundStarted) {
            return roundDurationMs;
        }
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

    /** Spawn energy and freeze rays on separate timers, up to the map cap. */
    public void maybeSpawnItem(long tickMs) {
        nextEnergySpawnInMs -= tickMs;
        if (nextEnergySpawnInMs <= 0) {
            if (items.size() < MAX_ITEMS_ON_MAP) {
                spawnItem(Item.Type.ENERGY);
            }
            nextEnergySpawnInMs = randomEnergySpawnDelayMs();
        }

        nextFreezeRaySpawnInMs -= tickMs;
        if (nextFreezeRaySpawnInMs <= 0) {
            if (items.size() < MAX_ITEMS_ON_MAP)
                spawnFreezeRayIfPossible();
            nextFreezeRaySpawnInMs = FREEZE_RAY_SPAWN_MS;
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
        for (Item item : new ArrayList<>(items)) {
            if (item.overlaps(p.x, p.y)) {
                applyItem(p, item);
                items.remove(item);
                System.out.println("[GameState] " + p.name + " picked up " + item.type);
            }
        }
    }

    private void applyItem(PlayerState p, Item item) {
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
            Item.Type type = shouldSpawnFreezeRay() ? Item.Type.FREEZE_RAY : Item.Type.ENERGY;
            if (type == Item.Type.FREEZE_RAY) {
                spawnFreezeRayIfPossible();
            } else {
                spawnItem(Item.Type.ENERGY);
            }
        }
    }

    private boolean shouldSpawnFreezeRay() {
        return countItemsOfType(Item.Type.FREEZE_RAY) < MAX_FREEZE_RAYS_ON_MAP && rng.nextInt(4) == 0;
    }

    private void spawnFreezeRayIfPossible() {
        if (countItemsOfType(Item.Type.FREEZE_RAY) >= MAX_FREEZE_RAYS_ON_MAP) {
            return;
        }
        spawnItem(Item.Type.FREEZE_RAY);
    }

    private void spawnItem(Item.Type type) {
        int x = 20 + rng.nextInt(MAP_WIDTH - 40);
        int y = 20 + rng.nextInt(MAP_HEIGHT - 40);
        items.add(new Item(type, x, y));
    }

    private int countItemsOfType(Item.Type type) {
        int count = 0;
        for (Item item : items) {
            if (item.type == type) {
                count++;
            }
        }
        return count;
    }

    private long randomEnergySpawnDelayMs() {
        return MIN_ENERGY_SPAWN_MS
                + rng.nextInt((int) (MAX_ENERGY_SPAWN_MS - MIN_ENERGY_SPAWN_MS + 1));
    }

    public List<Zone> getZones() {
        return zones;
    }

    public List<Item> getItems() {
        return items;
    }
}
