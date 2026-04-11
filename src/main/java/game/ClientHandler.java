package game;

import java.io.*;
import java.net.Socket;

/**
 * ClientHandler - Manages one TCP connection for one player.
 *
 * Client:
 * 1. Wait for JOIN|<playerName>
 * 2. Register the player in GameState, get back a playerID.
 * 3. Register this writer with GameLoop so the broadcast loop
 * can push STATE updates to this client every tick.
 * 4. Send JOIN_OK|<playerID>|<udpPort> back to the client.
 * 5. Keep the connection alive, listening only for QUIT or the
 * kill-switch flag being set server-side(in case of misbehaving clients).
 * 6. On disconnect/QUIT: remove player from GameState, unregister writer.
 *
 * TCP message reference:
 * Client → Server: JOIN|<name>
 * QUIT
 * Server → Client: JOIN_OK|<playerID>|<udpPort>
 * STATE|<timeMs>|PLAYERS:...|ZONES:...|ITEMS:... (every tick)
 * FINAL|<name:score,...> (round end)
 * KILLED|You were kicked by an admin (kill-switch)
 * ERROR|<code>|<message>
 *
 * @author Requiem493 help from claude.ai
 */
public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final GameState gameState;
    private final GameLoop gameLoop;

    // Filled in once JOIN is processed
    private String playerId = null;
    private BufferedWriter out = null;

    public ClientHandler(Socket socket, GameState gameState, GameLoop gameLoop) {
        this.clientSocket = socket;
        this.gameState = gameState;
        this.gameLoop = gameLoop;
    }

    @Override
    public void run() {
        try {
            // Tune socket for low-latency game traffic
            clientSocket.setKeepAlive(true);
            clientSocket.setSoTimeout(0); // no read timeout during game
            clientSocket.setReceiveBufferSize(65536);
            clientSocket.setSendBufferSize(65536);
            clientSocket.setTcpNoDelay(true);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
            out = new BufferedWriter(
                    new OutputStreamWriter(clientSocket.getOutputStream()));

            System.out.println("[ClientHandler] Connection from "
                    + clientSocket.getRemoteSocketAddress());

            // ── Step 1: Handshake — expect JOIN|<name> ────────────────────
            if (!handleJoin(in, out))
                return; // malformed or timed-out join

            // ── Step 2: Listen for QUIT or kill-switch ────────────────────
            // The GameLoop pushes STATE lines to `out` independently.
            // This thread just needs to detect disconnect / voluntary QUIT.
            clientSocket.setSoTimeout(5000); // poll every 5s for kill-switch

            while (true) {

                // Check kill-switch flag (set by admin console)
                PlayerState p = gameState.getPlayer(playerId);
                if (p != null && p.killed) {
                    sendLine("KILLED|You were kicked by an admin");
                    System.out.println("[ClientHandler] Kill-switch: " + playerId);
                    break;
                }

                // Check round ended
                if (gameState.hasRoundEnded()) {
                    // Final scores already broadcast by GameLoop; just close cleanly
                    System.out.println("[ClientHandler] Round over, closing " + playerId);
                    break;
                }

                // Try to read an incoming message (QUIT or anything else)
                try {
                    String line = in.readLine();
                    if (line == null) {
                        System.out.println("[ClientHandler] Client disconnected: " + playerId);
                        break;
                    }
                    if (line.equalsIgnoreCase("QUIT")) {
                        sendLine("BYE|Thanks for playing ChronoArena!");
                        System.out.println("[ClientHandler] QUIT from " + playerId);
                        break;
                    }
                    // Any other TCP message from the client during a round is ignored;
                    // game actions come via UDP.
                } catch (java.net.SocketTimeoutException e) {
                    // Normal — just looping to check kill-switch / round state
                }
            }

        } catch (IOException e) {
            System.out.println("[ClientHandler] IO error for " + playerId
                    + ": " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOIN HANDSHAKE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Reads the JOIN message, registers the player, tells the GameLoop to
     * include this writer in its broadcast set.
     * Returns false if the handshake fails.
     */
    private boolean handleJoin(BufferedReader in, BufferedWriter out) throws IOException {

        // Give the client 10 seconds to send JOIN
        clientSocket.setSoTimeout(10000);

        String firstLine;
        try {
            firstLine = in.readLine();
        } catch (java.net.SocketTimeoutException e) {
            sendLine("ERROR|408|Join timeout — send JOIN|<name> within 10 seconds");
            return false;
        }

        if (firstLine == null || !firstLine.startsWith("JOIN|")) {
            sendLine("ERROR|400|Expected JOIN|<playerName>");
            return false;
        }

        String[] parts = firstLine.split("\\|", 2);
        String name = (parts.length > 1 && !parts[1].isBlank()) ? parts[1].trim() : "Unknown";


        // Register in game state — get back an assigned ID
        playerId = gameState.addPlayer(name);

        // Register writer with GameLoop BEFORE sending JOIN_OK so no STATE
        // packets are missed by the client
        gameLoop.registerClient(out);

        // Start the round once the first player has fully joined.
        gameState.startRound();

        // Tell the client their ID and which UDP port to send actions to
        // (client reads udpPort from game.properties itself, but we echo it
        // here for convenience / future-proofing)
        sendLine("JOIN_OK|" + playerId + "|" + name);
        System.out.println("[ClientHandler] Player joined: " + name + " → " + playerId);

        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════════════════════════════════════════

    private void cleanup() {
        // Remove from broadcast set first so no more STATE writes happen
        if (out != null)
            gameLoop.unregisterClient(out);

        // Remove from game state (releases zone ownership etc.)
        if (playerId != null)
            gameState.removePlayer(playerId);

        try {
            clientSocket.close();
        } catch (IOException ignored) {
        }

        System.out.println("[ClientHandler] Cleaned up " + playerId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER
    // ══════════════════════════════════════════════════════════════════════════

    private void sendLine(String message) {
        try {
            out.write(message);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            System.err.println("[ClientHandler] Send failed: " + e.getMessage());
        }
    }
}
