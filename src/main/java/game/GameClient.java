package game;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * GameClient - Terminal-based ChronoArena client (no GUI for now).
 *
 * Contains:
 * - main() load properties, name prompt, connect, input loop
 * - TCPListener inner Runnable — reads STATE/FINAL/KILLED from server,
 * prints updates to console
 * - UDPSender inner class — fires MOVE packets to server
 * - LocalGameState inner class — parsed snapshot printed to console(debug
 * purposes only)
 *
 * Threads:
 * main thread → handles console input (Scanner)
 * TCPListener thread → reads server broadcasts, prints to console
 *
 * Console commands (type and press Enter):
 * w / a / s / d — move up / left / down / right
 * state — print the latest game state
 * quit — leave the game
 *
 * @author Requiem493, help from claude.ai
 */
public class GameClient {

    public static String serverIP;
    public static int tcpPort = 8000;
    public static int udpPort = 9000;
    public static String name = ""; 

    // tcp port should be 8000, udp port should be 9000, server IP can change
    public GameClient(String IP) {
        GameClient.serverIP = IP;
    }

    // Session info (set after JOIN_OK)
    public static volatile String myPlayerID = null;
    static volatile String myName = null;

    // Latest game state written by TCPListener, read by main
    public static volatile LocalGameState localState = new LocalGameState();

    // Network handles
    public static Socket tcpSocket;
    public static BufferedWriter tcpOut;
    public static DatagramSocket udpSocket;
    public static InetAddress serverAddr;

    // MAIN

    public static void main(String[] args) throws Exception {

        // 1. Read server IP from command line argument
        if (args.length < 1) {
            System.out.println("Usage: java -jar gameclient.jar <serverIP>");
            System.out.println("Example: java -jar gameclient.jar 192.168.1.10");
            return;
        }
        serverIP = args[0];

        // 2. Use try-with-resources so Scanner, Socket, and DatagramSocket
        // are always closed on exit — even on early returns or exceptions
        try (Scanner scanner = new Scanner(System.in);
                Socket socket = new Socket(serverIP, tcpPort);
                DatagramSocket dgram = new DatagramSocket()) {

            tcpSocket = socket;
            udpSocket = dgram;
            tcpSocket.setKeepAlive(true);
            tcpSocket.setTcpNoDelay(true);

            /* 
            // 3. Ask player name via console
            System.out.print("Enter your player name: ");
            name = scanner.nextLine().trim();
            if (name.isBlank()) {
                System.out.println("No name entered. Exiting.");
                return;
            }
                */
            myName = name;

            // 4. Connect TCP streams
            System.out.println("Connecting to " + serverIP + ":" + tcpPort + " ...");
            tcpOut = new BufferedWriter(new OutputStreamWriter(tcpSocket.getOutputStream()));
            BufferedReader tcpIn = new BufferedReader(
                    new InputStreamReader(tcpSocket.getInputStream()));

            // 5. Send JOIN
            writeTCP("JOIN|" + myName);

            // 6. Read JOIN_OK|<playerID>|<n>
            String joinReply = tcpIn.readLine();
            if (joinReply == null || !joinReply.startsWith("JOIN_OK|")) {
                System.out.println("Server rejected join: " + joinReply);
                return;
            }
            String[] joinParts = joinReply.split("\\|");
            myPlayerID = joinParts[1];
            System.out.println("Joined successfully — you are " + myName + " [" + myPlayerID + "]");

            // 7. Resolve server address for UDP
            serverAddr = InetAddress.getByName(serverIP);

            // 8. Start TCP listener thread
            Thread tcpThread = new Thread(new TCPListener(tcpIn));
            tcpThread.setDaemon(true);
            tcpThread.setName("TCP-Listener");
            tcpThread.start();

            // 9. Print controls
            printControls();

            // 10. Console input loop
            UDPSender udp = new UDPSender();

            while (true) {
                String input = scanner.nextLine().trim().toLowerCase();

                if (input.isBlank())
                    continue;

                switch (input) {
                    case "w" -> udp.sendMove(0, -1);
                    case "s" -> udp.sendMove(0, 1);
                    case "a" -> udp.sendMove(-1, 0);
                    case "d" -> udp.sendMove(1, 0);
                    case "wa", "aw" -> udp.sendMove(-1, -1);
                    case "wd", "dw" -> udp.sendMove(1, -1);
                    case "sa", "as" -> udp.sendMove(-1, 1);
                    case "sd", "ds" -> udp.sendMove(1, 1);
                    case "f" -> udp.sendFreeze();
                    case "state" -> printState();
                    case "help" -> printControls();
                    case "quit", "exit", "q" -> {
                        writeTCP("QUIT");
                        System.out.println("Leaving game. Goodbye!");
                        return;
                    }
                    default -> System.out.println("Unknown command. Type 'help' for controls.");
                }
            }
        }
    }

    // TCP LISTENER (background thread)

    static class TCPListener implements Runnable {
        private final BufferedReader in;

        TCPListener(BufferedReader in) {
            this.in = in;
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("STATE|")) {
                        localState = LocalGameState.parse(line);
                    } else if (line.startsWith("FINAL|")) {
                        localState = LocalGameState.parseFinal(line, localState);
                        printFinalScores(line);
                    } else if (line.startsWith("KILLED|")) {
                        handleForcedDisconnect(line.substring(7).trim(), 0);
                    } else if (line.startsWith("BYE|")) {
                        handleGracefulDisconnect(line.substring(4), 0);
                    } else {
                        System.out.println("[SERVER] " + line);
                    }
                }
                handleGracefulDisconnect("Server closed the connection.", 0);
            } catch (IOException e) {
                handleGracefulDisconnect("Lost connection to server.", 1);
            }
        }

        private void printFinalScores(String line) {
            System.out.println("\n" + "=".repeat(40));
            System.out.println("        GAME OVER — FINAL SCORES");
            System.out.println("=".repeat(40));
            String data = line.substring(6);
            String[] entries = data.split(",");
            int rank = 1;
            for (String entry : entries) {
                String[] parts = entry.split(":");
                String n = parts[0];
                String s = parts.length > 1 ? parts[1] : "?";
                String marker = (rank == 1) ? " <-- WINNER" : "";
                System.out.printf("  %d.  %-15s %s pts%s%n", rank++, n, s, marker);
            }
            System.out.println("=".repeat(40));
            System.out.println("Type 'quit' to exit.");
        }
    }

    // UDP SENDER

    static class UDPSender {
        private final AtomicLong seq = new AtomicLong(0);

        void sendMove(int dx, int dy) {
            send("MOVE|" + dx + "|" + dy);
        }

        void sendFreeze() {
            send("FREEZE");
        }

        private void send(String action) {
            if (myPlayerID == null)
                return;
            String msg = seq.incrementAndGet() + "|" + myPlayerID + "|" + action;
            try {
                byte[] data = msg.getBytes();
                DatagramPacket pkt = new DatagramPacket(data, data.length, serverAddr, udpPort);
                udpSocket.send(pkt);
            } catch (IOException e) {
                System.err.println("[UDP] Send failed: " + e.getMessage());
            }
        }
    }

    // LOCAL GAME STATE (parsed from STATE broadcast)

    public static class LocalGameState {
        public long timeRemainingMs = 0;
        public boolean isFinal = false;
        public Map<String, PlayerData> players = new LinkedHashMap<>();
        public List<ZoneData> zones = new ArrayList<>();
        public List<ItemData> items = new ArrayList<>();
        public List<FinalScoreData> finalScores = new ArrayList<>();

        public static class PlayerData {
            public String id, name;
            public int x, y, score;
            public boolean frozen, hasWeapon, speedBoost;
        }

        public static class ZoneData {
            public String id, owner, status;
            public double capturePercent;
        }

        public static class ItemData {
            public String id, type;
            public int x, y;
        }

        public static class FinalScoreData {
            public String name;
            public int score;
        }

        // STATE|<timeMs>|PLAYERS:<p,...>|ZONES:<z,...>|ITEMS:<i,...>
        // Player: id:name:x:y:score:frozen:hasWeapon:speedBoost
        // Zone: id:owner:status:capturePercent
        // Item: id:type:x:y
        static LocalGameState parse(String line) {
            LocalGameState s = new LocalGameState();
            try {
                String[] sections = line.split("\\|", 5);
                s.timeRemainingMs = Long.parseLong(sections[1]);

                String playersPart = sections[2].substring("PLAYERS:".length());
                if (!playersPart.isEmpty()) {
                    for (String entry : playersPart.split(",")) {
                        String[] f = entry.split(":");
                        if (f.length < 8)
                            continue;
                        PlayerData p = new PlayerData();
                        p.id = f[0];
                        p.name = f[1];
                        p.x = Integer.parseInt(f[2]);
                        p.y = Integer.parseInt(f[3]);
                        p.score = Integer.parseInt(f[4]);
                        p.frozen = f[5].equals("1");
                        p.hasWeapon = f[6].equals("1");
                        // p.speedBoost = f[7].equals("1");
                        s.players.put(p.id, p);
                    }
                }

                String zonesPart = sections[3].substring("ZONES:".length());
                if (!zonesPart.isEmpty()) {
                    for (String entry : zonesPart.split(",")) {
                        String[] f = entry.split(":");
                        if (f.length < 4)
                            continue;
                        ZoneData z = new ZoneData();
                        z.id = f[0];
                        z.owner = f[1];
                        z.status = f[2];
                        z.capturePercent = Double.parseDouble(f[3]);
                        s.zones.add(z);
                    }
                }

                String itemsPart = sections[4].substring("ITEMS:".length());
                if (!itemsPart.isEmpty()) {
                    for (String entry : itemsPart.split(",")) {
                        String[] f = entry.split(":");
                        if (f.length < 4)
                            continue;
                        ItemData item = new ItemData();
                        item.id = f[0];
                        item.type = f[1];
                        item.x = Integer.parseInt(f[2]);
                        item.y = Integer.parseInt(f[3]);
                        s.items.add(item);
                    }
                }

            } catch (Exception e) {
                System.err.println("[Parse] Bad STATE line: " + e.getMessage());
            }
            return s;
        }

        static LocalGameState parseFinal(String line, LocalGameState previousState) {
            LocalGameState s = new LocalGameState();
            s.isFinal = true;
            s.timeRemainingMs = 0;

            if (previousState != null) {
                s.players.putAll(previousState.players);
                s.zones.addAll(previousState.zones);
                s.items.addAll(previousState.items);
            }

            try {
                String data = line.substring("FINAL|".length());
                if (!data.isEmpty()) {
                    for (String entry : data.split(",")) {
                        String[] parts = entry.split(":");
                        if (parts.length < 2) {
                            continue;
                        }

                        FinalScoreData score = new FinalScoreData();
                        score.name = parts[0];
                        score.score = Integer.parseInt(parts[1]);
                        s.finalScores.add(score);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Parse] Bad FINAL line: " + e.getMessage());
            }

            return s;
        }
    }

    // CONSOLE HELPERS

    static void printState() {
        LocalGameState s = localState;
        long secs = s.timeRemainingMs / 1000;
        System.out.println("\n--- Game State  ["
                + String.format("%02d:%02d", secs / 60, secs % 60) + " left] ---");

        System.out.println("PLAYERS:");
        for (LocalGameState.PlayerData p : s.players.values()) {
            String me = p.id.equals(myPlayerID) ? " <- YOU" : "";
            String frozen = p.frozen ? " [FROZEN]" : "";
            String weapon = p.hasWeapon ? " [WEAPON]" : "";
            // String speed = p.speedBoost ? " [SPEED]" : "";
            System.out.printf("  %-4s %-12s pos=(%3d,%3d) score=%-5d%s%s%n",
                    p.id, p.name, p.x, p.y, p.score, frozen, weapon + me);
        }

        System.out.println("ZONES:");
        for (LocalGameState.ZoneData z : s.zones) {
            String owner = z.owner.equals("NONE") ? "unclaimed" : z.owner;
            String cap = z.status.equals("CAPTURING")
                    ? String.format(" (%.0f%%)", z.capturePercent * 100)
                    : "";
            String remaining = z.owner.equals("NONE")
                    ? ""
                    : String.format(" (%.1fs left)", z.capturePercent * Zone.OWNERSHIP_DURATION_MS / 1000.0);
            System.out.printf("  %-4s %-10s owner=%-6s%s%n",
                    z.id, z.status, owner, cap + remaining);
        }

        System.out.println("ITEMS (" + s.items.size() + " on map):");
        for (LocalGameState.ItemData item : s.items) {
            System.out.printf("  %-8s %-7s at (%d,%d)%n",
                    item.id, item.type, item.x, item.y);
        }
        System.out.println("---");
    }

    static void printControls() {
        System.out.println("\n" + "=".repeat(40));
        System.out.println("  ChronoArena Controls");
        System.out.println("=".repeat(40));
        System.out.println("  w / s / a / d    Move up/down/left/right");
        System.out.println("  wa wd sa sd      Diagonal movement");
        System.out.println("  f                Use freeze-ray weapon");
        System.out.println("  state            Print current game state");
        System.out.println("  help             Show this menu");
        System.out.println("  quit             Leave the game");
        System.out.println("=".repeat(40));
        System.out.println("Walk near items to collect (server detects on MOVE).");
        System.out.println("Items: ENERGY=+15pts, FREEZE_RAY=freeze nearest player\n");
    }

    // HELPERS

    static void writeTCP(String message) {
        try {
            tcpOut.write(message);
            tcpOut.newLine();
            tcpOut.flush();
        } catch (IOException e) {
            System.err.println("[TCP] Send failed: " + e.getMessage());
        }
    }

    public static void loadProperties() {
        Properties props = new Properties();
        File file = new File("game.properties");
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
                serverIP = props.getProperty("server.ip", serverIP);
                tcpPort = Integer.parseInt(props.getProperty("server.tcp.port", "" + tcpPort));
                udpPort = Integer.parseInt(props.getProperty("server.udp.port", "" + udpPort));
                System.out.println("[Client] Config loaded from game.properties");
            } catch (IOException e) {
                System.err.println("[Client] Could not read game.properties — using defaults");
            }
        } else {
            System.out.println("[Client] game.properties not found — will prompt for IP");
        }
    }

    private static void handleForcedDisconnect(String reason, int exitCode) {
        String message = (reason == null || reason.isBlank())
                ? "You have been kicked by an admin."
                : reason;
        System.out.println("\n[SERVER] " + message);
        disconnect();
        showPopupIfGui("Kicked", message);
        System.exit(exitCode);
    }

    private static void handleGracefulDisconnect(String message, int exitCode) {
        if (message != null && !message.isBlank()) {
            System.out.println("\n[SERVER] " + message);
        }
        disconnect();
        System.exit(exitCode);
    }

    private static void showPopupIfGui(String title, String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        boolean hasVisibleFrame = false;
        for (Frame frame : Frame.getFrames()) {
            if (frame.isDisplayable()) {
                hasVisibleFrame = true;
                break;
            }
        }
        if (!hasVisibleFrame) {
            return;
        }

        Runnable showDialog = () -> JOptionPane.showMessageDialog(
                null,
                message,
                title,
                JOptionPane.WARNING_MESSAGE);

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run();
            } else {
                SwingUtilities.invokeAndWait(showDialog);
            }
        } catch (Exception e) {
            System.err.println("[Client] Could not display popup: " + e.getMessage());
        }
    }

    public static synchronized void connect(String IP) throws Exception {
        disconnect();

        serverIP = IP;
        myName = name;
        localState = new LocalGameState();

        try {
            tcpSocket = new Socket(serverIP, tcpPort);
            udpSocket = new DatagramSocket();
            tcpSocket.setKeepAlive(true);
            tcpSocket.setTcpNoDelay(true);

            System.out.println("Connecting to " + serverIP + ":" + tcpPort + " ...");
            tcpOut = new BufferedWriter(new OutputStreamWriter(tcpSocket.getOutputStream()));
            BufferedReader tcpIn = new BufferedReader(
                    new InputStreamReader(tcpSocket.getInputStream()));

            writeTCP("JOIN|" + myName);

            String joinReply = tcpIn.readLine();
            if (joinReply == null || !joinReply.startsWith("JOIN_OK|")) {
                throw new IOException("Server rejected join: " + joinReply);
            }

            String[] joinParts = joinReply.split("\\|");
            myPlayerID = joinParts[1];
            System.out.println("Joined successfully - you are " + myName + " [" + myPlayerID + "]");

            serverAddr = InetAddress.getByName(serverIP);

            Thread tcpThread = new Thread(new TCPListener(tcpIn));
            tcpThread.setDaemon(true);
            tcpThread.setName("TCP-Listener");
            tcpThread.start();
        } catch (Exception e) {
            disconnect();
            throw e;
        }
    }

    public static synchronized void disconnect() {
        myPlayerID = null;
        myName = null;
        localState = new LocalGameState();

        if (tcpOut != null) {
            try {
                tcpOut.close();
            } catch (IOException e) {
                System.err.println("[Client] Could not close TCP output: " + e.getMessage());
            }
            tcpOut = null;
        }

        if (tcpSocket != null) {
            try {
                tcpSocket.close();
            } catch (IOException e) {
                System.err.println("[Client] Could not close TCP socket: " + e.getMessage());
            }
            tcpSocket = null;
        }

        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }

        serverAddr = null;
    }
}
