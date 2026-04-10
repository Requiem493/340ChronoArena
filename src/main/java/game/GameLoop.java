package game;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * GameLoop - Fixed-rate game loop running at TICK_RATE_MS per tick.
 *
 * Each tick:
 * 1. Drains the action queue and processes every action.
 * 2. Ticks zone logic (capture progress, grace timers, point awards).
 * 3. Maybe spawns a new item.
 * 4. Checks if the round is over.
 * 5. Serialises GameState and broadcasts to all TCP clients.
 *
 * @author Requiem493, help from claude.ai
 */
public class GameLoop implements Runnable {

    public static final long TICK_RATE_MS = 50; // 20 ticks/second

    private final GameState gameState;
    private volatile boolean running = true;

    // All connected TCP client writers — thread-safe set
    private final Set<BufferedWriter> clientWriters = ConcurrentHashMap.newKeySet();

    public GameLoop(GameState gameState) {
        this.gameState = gameState;
    }

    // ── Client registration (called by ClientHandler threads) ─────────────────

    public void registerClient(BufferedWriter writer) {
        clientWriters.add(writer);
    }

    public void unregisterClient(BufferedWriter writer) {
        clientWriters.remove(writer);
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        System.out.println("[GameLoop] Started at " + TICK_RATE_MS + "ms/tick");
        long lastTick = System.currentTimeMillis();

        while (running) {
            long now = System.currentTimeMillis();

            if (!gameState.hasRoundStarted()) {
                lastTick = now;
                sleepForTick(now);
                continue;
            }

            if (!gameState.isRunning()) {
                break;
            }

            long delta = now - lastTick;
            lastTick = now;

            // 1. Process all queued player actions
            for (String action : gameState.drainActions()) {
                gameState.processAction(action);
            }

            // 2. Tick zone capture/grace logic and award points
            gameState.tickZones(delta);

            // 2b. Expire temporary status effects like freeze
            gameState.tickStatusEffects();

            // 3. Maybe spawn a new item
            gameState.maybeSpawnItem(TICK_RATE_MS);

            // 4. Check round end
            if (gameState.timeRemainingMs() <= 0) {
                gameState.endRound();
                broadcast(gameState.serializeFinalScores());
                System.out.println("[GameLoop] Round over — final scores broadcast.");
                break;
            }

            // 5. Broadcast state to all clients
            broadcast(gameState.serializeLiveState());

            // Sleep to maintain tick rate
            sleepForTick(now);
        }

        System.out.println("[GameLoop] Stopped.");
    }

    public void stop() {
        running = false;
    }

    private void sleepForTick(long tickStartMs) {
        long elapsed = System.currentTimeMillis() - tickStartMs;
        long sleep = TICK_RATE_MS - elapsed;
        if (sleep <= 0) {
            return;
        }

        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    /** Send one line to every registered TCP client. Removes dead writers. */
    private void broadcast(String message) {
        Set<BufferedWriter> dead = new HashSet<>();
        for (BufferedWriter writer : clientWriters) {
            try {
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                dead.add(writer);
            }
        }
        clientWriters.removeAll(dead);
    }

    /** Send a targeted message to one client (used by ClientHandler). */
    public void sendTo(BufferedWriter writer, String message) {
        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            clientWriters.remove(writer);
        }
    }
}
