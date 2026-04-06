package game;

import java.io.*;
import java.net.*;

/**
 * MainServer - Entry point for the ChronoArena game server.
 *
 * Usage: java MainServer <ip> <tcpPort> <udpPort> <roundSeconds>
 * Example: java MainServer 192.168.1.10 8000 9000 180
 *
 * Protocol summary:
 * TCP (clients -> server): JOIN|<playerName>
 * QUIT
 * TCP (server -> clients, every tick): STATE|... or FINAL|...
 * UDP (clients -> server): <seq>|<playerID>|MOVE|<dx>|<dy>
 *
 * @author Requiem493, help from claude.ai
 */
public class MainServer {

    private static GameState gameState;
    private static GameLoop gameLoop;

    public static void main(String[] args) throws IOException {

        // ── 1. Parse args ─────────────────────────────────────────────────────
        if (args.length < 4) {
            System.out.println("Usage: java MainServer <ip> <tcpPort> <udpPort> <roundSeconds>");
            System.out.println("Example: java MainServer 192.168.1.10 8000 9000 180");
            return;
        }

        String ip = args[0]; // e.g. "192.168.1.10"
        int tcpPort = Integer.parseInt(args[1]);
        int udpPort = Integer.parseInt(args[2]);
        long roundSecs = Long.parseLong(args[3]);

        InetAddress bindAddress = InetAddress.getByName(ip);

        System.out.println("=".repeat(55));
        System.out.println("  ChronoArena Server");
        System.out.println("  Bind IP  : " + ip);
        System.out.println("  TCP port : " + tcpPort);
        System.out.println("  UDP port : " + udpPort);
        System.out.println("  Round    : " + roundSecs + "s");
        System.out.println("=".repeat(55));

        // ── 2. Create shared game objects ─────────────────────────────────────
        gameState = new GameState(roundSecs * 1000);
        gameLoop = new GameLoop(gameState);

        // ── 3. UDP action receiver ────────────────────────────────────────────
        Thread udpThread = new Thread(new UDPReceiver(gameState, udpPort, bindAddress));
        udpThread.setDaemon(true);
        udpThread.setName("UDP-Receiver");
        udpThread.start();
        System.out.println("[Server] UDP receiver started on " + ip + ":" + udpPort);

        // ── 4. Game loop ──────────────────────────────────────────────────────
        Thread loopThread = new Thread(gameLoop);
        loopThread.setDaemon(true);
        loopThread.setName("GameLoop");
        loopThread.start();
        System.out.println("[Server] Game loop started");

        // ── 5. Admin console (KILL_SWITCH) ────────────────────────────────────
        Thread adminThread = new Thread(() -> adminConsole());
        adminThread.setDaemon(true);
        adminThread.setName("Admin-Console");
        adminThread.start();

        // ── 6. TCP accept loop — bind to the specified IP ─────────────────────
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, tcpPort), 50);
        System.out.println("[Server] Listening for players on " + ip + ":" + tcpPort + "\n");

        while (true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("[Server] New connection from "
                    + clientSocket.getRemoteSocketAddress());

            Thread t = new Thread(new ClientHandler(clientSocket, gameState, gameLoop));
            t.setDaemon(true);
            t.start();
        }

    }

    // ── Admin console (KILL_SWITCH) ───────────────────────────────────────────
    // Commands: kill <playerID> | list | quit

    private static void adminConsole() {
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("[Admin] Commands: kill <id> | list | quit");
            String line;
            while ((line = console.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("kill ")) {
                    String pid = line.substring(5).trim();
                    gameState.killPlayer(pid);
                    System.out.println("[Admin] Kill-switch activated for " + pid);

                } else if (line.equals("list")) {
                    System.out.println("[Admin] Connected players:");
                    for (PlayerState p : gameState.allPlayers()) {
                        System.out.printf("  %-6s %-15s score=%d%n",
                                p.id, p.name, p.score);
                    }

                } else if (line.equals("quit")) {
                    System.out.println("[Admin] Ending round early.");
                    gameState.endRound();

                } else {
                    System.out.println("[Admin] Unknown command. Use: kill <id> | list | quit");
                }
            }
        } catch (IOException e) {
            System.err.println("[Admin] Console error: " + e.getMessage());
        }
    }
}