package game;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

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
    private static final int ADMIN_REFRESH_MS = 100;

    private static GameState gameState;
    private static GameLoop gameLoop;
    private static AdminFrame adminFrame;

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
        launchAdminView();

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

    private static void launchAdminView() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[Admin] Headless environment detected - skipping admin view.");
            return;
        }

        SwingUtilities.invokeLater(() -> {
            adminFrame = new AdminFrame();
            adminFrame.setVisible(true);
        });
    }

    private static void refreshAdminView() {
        SwingUtilities.invokeLater(() -> {
            if (adminFrame != null) {
                adminFrame.refreshNow();
            }
        });
    }

    private static void adminConsole() {
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("[Admin] Commands: kill <id> | list | status | quit");
            String line;
            while ((line = console.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("kill ")) {
                    String pid = line.substring(5).trim();
                    gameState.killPlayer(pid);
                    System.out.println("[Admin] Kill-switch activated for " + pid);
                    refreshAdminView();

                } else if (line.equals("list")) {
                    System.out.println("[Admin] Connected players:");
                    for (PlayerState p : gameState.allPlayers()) {
                        System.out.printf("  %-6s %-15s score=%d%n",
                                p.id, p.name, p.score);
                    }

                } else if (line.equals("status")) {
                    System.out.println(buildStatusSummary(buildAdminSnapshot()));

                } else if (line.equals("quit")) {
                    System.out.println("[Admin] Ending round early.");
                    gameState.endRound();
                    refreshAdminView();

                } else {
                    System.out.println("[Admin] Unknown command. Use: kill <id> | list | status | quit");
                }
            }
        } catch (IOException e) {
            System.err.println("[Admin] Console error: " + e.getMessage());
        }
    }

    private static AdminSnapshot buildAdminSnapshot() {
        GameClient.LocalGameState liveState = GameClient.LocalGameState.parse(gameState.serializeLiveState());
        if (gameState.hasRoundEnded()) {
            GameClient.LocalGameState finalState = GameClient.LocalGameState.parseFinal(
                    gameState.serializeFinalScores(),
                    liveState);
            return new AdminSnapshot(finalState, RoundPhase.FINAL);
        }
        if (!gameState.hasRoundStarted()) {
            return new AdminSnapshot(liveState, RoundPhase.WAITING);
        }
        return new AdminSnapshot(liveState, RoundPhase.LIVE);
    }

    private static String buildStatusSummary(AdminSnapshot snapshot) {
        GameClient.LocalGameState state = snapshot.state;
        long secs = state.timeRemainingMs / 1000;
        StringBuilder sb = new StringBuilder();
        sb.append("ChronoArena Server Status\n");
        sb.append("=========================\n");
        sb.append("Round: ").append(snapshot.phase.label).append('\n');
        sb.append(String.format("Time Remaining: %02d:%02d%n", secs / 60, secs % 60));
        sb.append("Players Connected: ").append(state.players.size()).append('\n');
        sb.append("Items On Map: ").append(state.items.size()).append("\n\n");

        List<GameClient.LocalGameState.PlayerData> players = new ArrayList<>(state.players.values());
        players.sort(Comparator
                .comparingInt((GameClient.LocalGameState.PlayerData p) -> p.score).reversed()
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(p -> p.id));

        sb.append("PLAYERS:\n");
        if (players.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (GameClient.LocalGameState.PlayerData player : players) {
                sb.append(String.format(
                        "  %-4s %-12s pos=(%3d,%3d) score=%-4d %s%n",
                        player.id,
                        player.name,
                        player.x,
                        player.y,
                        player.score,
                        buildPlayerStatus(player)));
            }
        }

        sb.append("\nZONES:\n");
        for (GameClient.LocalGameState.ZoneData zone : state.zones) {
            String owner = zone.owner.equals("NONE") ? "unclaimed" : zone.owner;
            String detail = zone.owner.equals("NONE")
                    ? String.format(" %3.0f%%", zone.capturePercent * 100)
                    : String.format(" %.1fs left", zone.capturePercent * Zone.OWNERSHIP_DURATION_MS / 1000.0);
            sb.append(String.format("  %-4s %-10s owner=%-10s%s%n",
                    zone.id,
                    zone.status,
                    owner,
                    detail));
        }

        sb.append("\nITEMS:\n");
        if (state.items.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (GameClient.LocalGameState.ItemData item : state.items) {
                sb.append(String.format("  %-8s %-10s (%d,%d)%n",
                        item.id,
                        item.type,
                        item.x,
                        item.y));
            }
        }

        if (state.isFinal && !state.finalScores.isEmpty()) {
            sb.append("\nFINAL LEADERBOARD:\n");
            for (int i = 0; i < state.finalScores.size(); i++) {
                GameClient.LocalGameState.FinalScoreData entry = state.finalScores.get(i);
                sb.append(String.format("  %d. %-12s %d pts%n", i + 1, entry.name, entry.score));
            }
        }

        return sb.toString();
    }

    private static String buildPlayerStatus(GameClient.LocalGameState.PlayerData player) {
        if (player.frozen) {
            return "Frozen";
        }
        if (player.hasWeapon) {
            return "Freeze";
        }
        return "Active";
    }

    private enum RoundPhase {
        WAITING("Waiting for first player"),
        LIVE("Round live"),
        FINAL("Round ended");

        private final String label;

        RoundPhase(String label) {
            this.label = label;
        }
    }

    private static final class AdminSnapshot {
        private final GameClient.LocalGameState state;
        private final RoundPhase phase;

        private AdminSnapshot(GameClient.LocalGameState state, RoundPhase phase) {
            this.state = state;
            this.phase = phase;
        }
    }

    private static final class AdminFrame extends JFrame {
        private final JLabel timerLabel = new JLabel("Time: 00:00");
        private final JLabel roundLabel = new JLabel("Waiting for first player");
        private final JLabel playerCountLabel = new JLabel("Players: 0");
        private final JComboBox<PlayerChoice> playerSelector = new JComboBox<>();
        private final JButton killButton = new JButton("Kill Player");
        private final JButton statusButton = new JButton("Status");
        private final AdminArenaPanel arenaPanel = new AdminArenaPanel();
        private final Timer refreshTimer;

        private AdminFrame() {
            super("ChronoArena Server Admin");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());
            setMinimumSize(new Dimension(980, 760));
            setLocationByPlatform(true);

            buildHud();
            add(arenaPanel, BorderLayout.CENTER);

            pack();
            refreshTimer = new Timer(ADMIN_REFRESH_MS, e -> refreshNow());
            refreshTimer.start();
            refreshNow();
        }

        private void buildHud() {
            timerLabel.setFont(new Font("DialogInput", Font.BOLD, 18));
            timerLabel.setForeground(Color.WHITE);
            roundLabel.setFont(new Font("DialogInput", Font.BOLD, 16));
            roundLabel.setForeground(new Color(180, 220, 255));
            playerCountLabel.setFont(new Font("DialogInput", Font.PLAIN, 14));
            playerCountLabel.setForeground(new Color(210, 220, 235));

            JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
            infoPanel.setOpaque(false);
            infoPanel.add(timerLabel);
            infoPanel.add(roundLabel);
            infoPanel.add(playerCountLabel);

            playerSelector.setPreferredSize(new Dimension(190, 30));
            killButton.addActionListener(e -> killSelectedPlayer());
            statusButton.addActionListener(e -> showStatusDialog());

            JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            controlsPanel.setOpaque(false);
            controlsPanel.add(playerSelector);
            controlsPanel.add(killButton);
            controlsPanel.add(statusButton);

            JPanel hud = new JPanel(new BorderLayout());
            hud.setBackground(new Color(24, 31, 40));
            hud.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            hud.add(infoPanel, BorderLayout.WEST);
            hud.add(controlsPanel, BorderLayout.EAST);
            add(hud, BorderLayout.NORTH);
        }

        private void killSelectedPlayer() {
            PlayerChoice choice = (PlayerChoice) playerSelector.getSelectedItem();
            if (choice == null) {
                JOptionPane.showMessageDialog(
                        this,
                        "No connected player is selected.",
                        "Kill Player",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            gameState.killPlayer(choice.playerId);
            refreshNow();
        }

        private void showStatusDialog() {
            JTextArea textArea = new JTextArea(buildStatusSummary(buildAdminSnapshot()), 20, 48);
            textArea.setEditable(false);
            textArea.setCaretPosition(0);
            textArea.setFont(new Font("DialogInput", Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(textArea);
            JOptionPane.showMessageDialog(this, scrollPane, "Server Status", JOptionPane.INFORMATION_MESSAGE);
        }

        private void refreshNow() {
            if (!isDisplayable()) {
                refreshTimer.stop();
                return;
            }

            AdminSnapshot snapshot = buildAdminSnapshot();
            long secs = snapshot.state.timeRemainingMs / 1000;
            timerLabel.setText(String.format("Time: %02d:%02d", secs / 60, secs % 60));
            roundLabel.setText(snapshot.phase.label);
            playerCountLabel.setText("Players: " + snapshot.state.players.size());
            refreshPlayerSelector(snapshot.state);
            arenaPanel.setSnapshot(snapshot);
        }

        private void refreshPlayerSelector(GameClient.LocalGameState state) {
            PlayerChoice previousSelection = (PlayerChoice) playerSelector.getSelectedItem();
            String selectedId = previousSelection == null ? null : previousSelection.playerId;

            List<GameClient.LocalGameState.PlayerData> players = new ArrayList<>(state.players.values());
            players.sort(Comparator
                    .comparingInt((GameClient.LocalGameState.PlayerData p) -> p.score).reversed()
                    .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(p -> p.id));

            playerSelector.removeAllItems();
            PlayerChoice selectedChoice = null;
            for (GameClient.LocalGameState.PlayerData player : players) {
                PlayerChoice choice = new PlayerChoice(player.id, player.name);
                playerSelector.addItem(choice);
                if (choice.playerId.equals(selectedId)) {
                    selectedChoice = choice;
                }
            }

            if (selectedChoice != null) {
                playerSelector.setSelectedItem(selectedChoice);
            } else if (playerSelector.getItemCount() > 0) {
                playerSelector.setSelectedIndex(0);
            }

            boolean hasPlayers = playerSelector.getItemCount() > 0;
            playerSelector.setEnabled(hasPlayers);
            killButton.setEnabled(hasPlayers);
        }
    }

    private static final class PlayerChoice {
        private final String playerId;
        private final String playerName;

        private PlayerChoice(String playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
        }

        @Override
        public String toString() {
            return playerId + " - " + playerName;
        }
    }

    private static final class AdminArenaPanel extends JPanel {
        private static final int ARENA_WIDTH = 800;
        private static final int ARENA_HEIGHT = 600;
        private static final int GRID_SIZE = 40;
        private static final int LEADERBOARD_WIDTH = 390;
        private static final int LEADERBOARD_ROW_HEIGHT = 30;
        private static final int LEADERBOARD_PADDING = 20;
        private static final double POSITION_SMOOTHING = 0.35;

        private final Map<String, RenderPosition> renderedPlayers = new HashMap<>();
        private AdminSnapshot snapshot = new AdminSnapshot(new GameClient.LocalGameState(), RoundPhase.WAITING);
        private boolean showLeaderboard = false;

        private AdminArenaPanel() {
            setPreferredSize(new Dimension(980, 680));
            setBackground(new Color(32, 44, 56));
            setFocusable(true);

            InputMap inputMap = getInputMap(WHEN_IN_FOCUSED_WINDOW);
            ActionMap actionMap = getActionMap();
            inputMap.put(KeyStroke.getKeyStroke("pressed TAB"), "leaderboardOn");
            inputMap.put(KeyStroke.getKeyStroke("released TAB"), "leaderboardOff");
            actionMap.put("leaderboardOn", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    showLeaderboard = true;
                    repaint();
                }
            });
            actionMap.put("leaderboardOff", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    showLeaderboard = false;
                    repaint();
                }
            });

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                }
            });
        }

        private void setSnapshot(AdminSnapshot snapshot) {
            this.snapshot = snapshot;
            updateRenderedPlayers(snapshot.state);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create();
            double scale = Math.min(
                    getWidth() / (double) ARENA_WIDTH,
                    getHeight() / (double) ARENA_HEIGHT);
            int viewportWidth = (int) Math.round(ARENA_WIDTH * scale);
            int viewportHeight = (int) Math.round(ARENA_HEIGHT * scale);
            int offsetX = (getWidth() - viewportWidth) / 2;
            int offsetY = (getHeight() - viewportHeight) / 2;

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2d.setColor(new Color(32, 44, 56));
            g2d.fillRect(0, 0, getWidth(), getHeight());

            g2d.translate(offsetX, offsetY);
            g2d.scale(scale, scale);

            g2d.setColor(new Color(173, 216, 230));
            g2d.fillRect(0, 0, ARENA_WIDTH, ARENA_HEIGHT);

            g2d.setColor(new Color(255, 255, 255, 80));
            for (int x = 0; x < ARENA_WIDTH; x += GRID_SIZE) {
                g2d.drawLine(x, 0, x, ARENA_HEIGHT);
            }
            for (int y = 0; y < ARENA_HEIGHT; y += GRID_SIZE) {
                g2d.drawLine(0, y, ARENA_WIDTH, y);
            }

            g2d.setStroke(new BasicStroke(3));
            for (GameClient.LocalGameState.ZoneData zone : snapshot.state.zones) {
                drawZone(g2d, zone);
            }

            for (GameClient.LocalGameState.ItemData item : snapshot.state.items) {
                drawItem(g2d, item);
            }

            for (GameClient.LocalGameState.PlayerData player : snapshot.state.players.values()) {
                drawPlayer(g2d, player);
            }

            if (snapshot.phase == RoundPhase.WAITING) {
                drawCenteredBanner(g2d, "Waiting for the first player to join");
            }

            if (snapshot.phase == RoundPhase.FINAL || showLeaderboard) {
                drawLeaderboardOverlay(g2d, snapshot.state);
            }

            g2d.dispose();
        }

        private void updateRenderedPlayers(GameClient.LocalGameState state) {
            renderedPlayers.keySet().removeIf(playerId -> !state.players.containsKey(playerId));
            for (GameClient.LocalGameState.PlayerData player : state.players.values()) {
                RenderPosition renderPos = renderedPlayers.computeIfAbsent(
                        player.id,
                        ignored -> new RenderPosition(player.x, player.y));
                renderPos.x += (player.x - renderPos.x) * POSITION_SMOOTHING;
                renderPos.y += (player.y - renderPos.y) * POSITION_SMOOTHING;

                if (Math.abs(player.x - renderPos.x) < 0.1) {
                    renderPos.x = player.x;
                }
                if (Math.abs(player.y - renderPos.y) < 0.1) {
                    renderPos.y = player.y;
                }
            }
        }

        private void drawItem(Graphics2D g2d, GameClient.LocalGameState.ItemData item) {
            if ("FREEZE_RAY".equals(item.type)) {
                g2d.setColor(new Color(120, 240, 255));
                g2d.fillRect(item.x - 10, item.y - 10, 20, 20);
                g2d.setColor(new Color(40, 120, 180));
                g2d.setStroke(new BasicStroke(2));
                g2d.drawRect(item.x - 10, item.y - 10, 20, 20);
            } else {
                g2d.setColor(new Color(255, 215, 0));
                g2d.fillOval(item.x - 8, item.y - 8, 16, 16);
                g2d.setColor(new Color(200, 160, 0));
                g2d.setStroke(new BasicStroke(2));
                g2d.drawOval(item.x - 8, item.y - 8, 16, 16);
            }
        }

        private void drawPlayer(Graphics2D g2d, GameClient.LocalGameState.PlayerData player) {
            RenderPosition renderPos = renderedPlayers.computeIfAbsent(
                    player.id,
                    ignored -> new RenderPosition(player.x, player.y));
            int drawX = (int) Math.round(renderPos.x);
            int drawY = (int) Math.round(renderPos.y);

            Color playerColor = new Color(220, 60, 60);
            if (player.frozen) {
                playerColor = new Color(140, 220, 255);
            } else if (player.hasWeapon) {
                playerColor = new Color(150, 110, 255);
            }

            g2d.setColor(playerColor);
            g2d.fillOval(drawX - 12, drawY - 12, 24, 24);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("DialogInput", Font.BOLD, 10));
            g2d.drawString(player.name, drawX - 18, drawY - 15);
            g2d.drawString(player.id, drawX - 18, drawY + 28);
            g2d.drawString(player.score + "pts", drawX - 18, drawY + 40);
        }

        private void drawCenteredBanner(Graphics2D g2d, String message) {
            int width = 330;
            int height = 42;
            int x = (ARENA_WIDTH - width) / 2;
            int y = 32;

            g2d.setColor(new Color(10, 18, 28, 215));
            g2d.fillRoundRect(x, y, width, height, 16, 16);
            g2d.setColor(new Color(130, 190, 255));
            g2d.setStroke(new BasicStroke(2f));
            g2d.drawRoundRect(x, y, width, height, 16, 16);

            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("DialogInput", Font.BOLD, 14));
            drawCenteredString(g2d, message, x + width / 2, y + 26);
        }

        private int[] getZonePos(String zoneId) {
            return switch (zoneId) {
                case "ZA" -> new int[]{100, 110};
                case "ZB" -> new int[]{500, 110};
                case "ZC" -> new int[]{300, 310};
                case "ZD" -> new int[]{100, 410};
                case "ZE" -> new int[]{500, 410};
                default -> new int[]{0, 0};
            };
        }

        private void drawZone(Graphics2D g2d, GameClient.LocalGameState.ZoneData zone) {
            int[] pos = getZonePos(zone.id);
            int x = pos[0];
            int y = pos[1];
            int width = 100;
            int height = 80;
            int inset = 4;

            Color zoneColor = getZoneColor(zone.status);
            Color outlineColor = zoneColor.darker();
            Color baseFill = new Color(zoneColor.getRed(), zoneColor.getGreen(), zoneColor.getBlue(), 45);
            Color progressFill = new Color(zoneColor.getRed(), zoneColor.getGreen(), zoneColor.getBlue(), 130);

            g2d.setColor(baseFill);
            g2d.fillRect(x, y, width, height);

            int innerWidth = width - inset * 2;
            int innerHeight = height - inset * 2;
            double clampedPercent = Math.max(0.0, Math.min(1.0, zone.capturePercent));
            int filledHeight = (int) Math.round(innerHeight * clampedPercent);
            if (filledHeight > 0) {
                int fillY = y + inset + (innerHeight - filledHeight);
                g2d.setColor(progressFill);
                g2d.fillRect(x + inset, fillY, innerWidth, filledHeight);
            }

            g2d.setColor(outlineColor);
            g2d.drawRect(x, y, width, height);

            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("DialogInput", Font.BOLD, 12));
            g2d.drawString(zone.id, x + 35, y + 25);
            g2d.setFont(new Font("DialogInput", Font.PLAIN, 10));
            g2d.drawString(zone.status, x + 5, y + 45);

            if (!zone.owner.equals("NONE")) {
                double secondsLeft = zone.capturePercent * Zone.OWNERSHIP_DURATION_MS / 1000.0;
                g2d.drawString(zone.owner, x + 5, y + 58);
                g2d.drawString(String.format("%.1fs left", secondsLeft), x + 5, y + 71);
            } else if (clampedPercent > 0.0) {
                g2d.drawString((int) Math.round(clampedPercent * 100) + "%", x + 5, y + 60);
            } else {
                g2d.drawString("UNCLAIMED", x + 5, y + 60);
            }
        }

        private Color getZoneColor(String status) {
            return switch (status) {
                case "CAPTURED" -> new Color(100, 200, 100, 100);
                case "CAPTURING" -> new Color(100, 150, 255, 100);
                case "CONTESTED" -> new Color(255, 165, 0, 100);
                default -> new Color(200, 200, 200, 80);
            };
        }

        private void drawLeaderboardOverlay(Graphics2D g2d, GameClient.LocalGameState state) {
            List<LeaderboardEntry> entries = buildLeaderboardEntries(state);
            int titleHeight = 34;
            int headerHeight = 28;
            int footerHeight = state.isFinal ? 28 : 0;
            int rowAreaTop = 70 + titleHeight + headerHeight;
            int rowCount = Math.max(1, entries.size());
            int height = rowAreaTop - 70 + footerHeight + rowCount * LEADERBOARD_ROW_HEIGHT + 18;
            int x = (ARENA_WIDTH - LEADERBOARD_WIDTH) / 2;
            int y = 70;
            int contentLeft = x + LEADERBOARD_PADDING;
            int contentRight = x + LEADERBOARD_WIDTH - LEADERBOARD_PADDING;
            int rankX = contentLeft;
            int playerX = contentLeft + 26;
            int scoreRightX = contentRight - 86;
            int stateCenterX = contentRight - 34;

            g2d.setColor(new Color(10, 18, 28, 225));
            g2d.fillRoundRect(x, y, LEADERBOARD_WIDTH, height, 18, 18);

            g2d.setColor(new Color(130, 190, 255));
            g2d.setStroke(new BasicStroke(2f));
            g2d.drawRoundRect(x, y, LEADERBOARD_WIDTH, height, 18, 18);

            g2d.setColor(new Color(255, 255, 255, 16));
            g2d.fillRoundRect(x + 10, y + 10, LEADERBOARD_WIDTH - 20, 34, 14, 14);

            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("DialogInput", Font.BOLD, 21));
            g2d.drawString(state.isFinal ? "Final Leaderboard" : "Leaderboard", contentLeft, y + 32);

            g2d.setColor(new Color(120, 170, 220, 180));
            g2d.drawLine(contentLeft, y + titleHeight + 12, contentRight, y + titleHeight + 12);

            g2d.setFont(new Font("DialogInput", Font.BOLD, 12));
            g2d.setColor(new Color(210, 225, 245));
            g2d.drawString("#", rankX, y + titleHeight + 28);
            g2d.drawString("Player", playerX, y + titleHeight + 28);
            drawRightAlignedString(g2d, "Score", scoreRightX, y + titleHeight + 28);
            drawCenteredString(g2d, "State", stateCenterX, y + titleHeight + 28);

            for (int i = 0; i < entries.size(); i++) {
                LeaderboardEntry entry = entries.get(i);
                int rowTop = rowAreaTop + i * LEADERBOARD_ROW_HEIGHT;
                int rowHeight = LEADERBOARD_ROW_HEIGHT - 4;

                if (i == 0) {
                    g2d.setColor(new Color(196, 148, 32, 120));
                    g2d.fillRoundRect(x + 10, rowTop, LEADERBOARD_WIDTH - 20, rowHeight, 12, 12);
                } else if (i % 2 == 0) {
                    g2d.setColor(new Color(255, 255, 255, 16));
                    g2d.fillRoundRect(x + 10, rowTop, LEADERBOARD_WIDTH - 20, rowHeight, 12, 12);
                }

                int baselineY = rowTop + 20;
                g2d.setFont(new Font("DialogInput", Font.BOLD, 12));
                g2d.setColor(new Color(180, 210, 240));
                g2d.drawString(String.valueOf(i + 1), rankX, baselineY);

                g2d.setColor(Color.WHITE);
                g2d.drawString(fitText(g2d, entry.name, 145), playerX, baselineY);

                g2d.setFont(new Font("DialogInput", Font.PLAIN, 11));
                g2d.setColor(new Color(200, 210, 220));
                drawRightAlignedString(g2d, entry.score + " pts", scoreRightX, baselineY);

                drawStatusChip(g2d, entry.status, stateCenterX, rowTop + 5);
            }

            if (state.isFinal) {
                g2d.setFont(new Font("DialogInput", Font.PLAIN, 10));
                g2d.setColor(new Color(190, 205, 225));
                g2d.drawString("Final scores from the server", contentLeft, y + height - 9);
            }
        }

        private List<LeaderboardEntry> buildLeaderboardEntries(GameClient.LocalGameState state) {
            List<LeaderboardEntry> entries = new ArrayList<>();

            if (state.isFinal && !state.finalScores.isEmpty()) {
                for (GameClient.LocalGameState.FinalScoreData finalScore : state.finalScores) {
                    entries.add(new LeaderboardEntry(finalScore.name, finalScore.score, "Final"));
                }
                return entries;
            }

            List<GameClient.LocalGameState.PlayerData> players = new ArrayList<>(state.players.values());
            players.sort(Comparator
                    .comparingInt((GameClient.LocalGameState.PlayerData p) -> p.score).reversed()
                    .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(p -> p.id));

            for (GameClient.LocalGameState.PlayerData player : players) {
                entries.add(new LeaderboardEntry(player.name, player.score, buildPlayerStatus(player)));
            }

            return entries;
        }

        private void drawStatusChip(Graphics2D g2d, String status, int centerX, int topY) {
            Color fillColor = switch (status) {
                case "Frozen" -> new Color(120, 190, 255, 190);
                case "Freeze" -> new Color(132, 98, 255, 190);
                case "Final" -> new Color(255, 196, 72, 190);
                default -> new Color(88, 174, 120, 190);
            };

            Color textColor = "Final".equals(status) ? new Color(60, 40, 0) : Color.WHITE;

            g2d.setFont(new Font("DialogInput", Font.BOLD, 10));
            int textWidth = g2d.getFontMetrics().stringWidth(status);
            int chipWidth = Math.max(56, textWidth + 16);
            int chipX = centerX - (chipWidth / 2);

            g2d.setColor(fillColor);
            g2d.fillRoundRect(chipX, topY, chipWidth, 18, 10, 10);

            g2d.setColor(textColor);
            drawCenteredString(g2d, status, centerX, topY + 13);
        }

        private void drawRightAlignedString(Graphics2D g2d, String text, int rightX, int baselineY) {
            int width = g2d.getFontMetrics().stringWidth(text);
            g2d.drawString(text, rightX - width, baselineY);
        }

        private void drawCenteredString(Graphics2D g2d, String text, int centerX, int baselineY) {
            int width = g2d.getFontMetrics().stringWidth(text);
            g2d.drawString(text, centerX - (width / 2), baselineY);
        }

        private String fitText(Graphics2D g2d, String text, int maxWidth) {
            if (g2d.getFontMetrics().stringWidth(text) <= maxWidth) {
                return text;
            }

            String ellipsis = "...";
            int ellipsisWidth = g2d.getFontMetrics().stringWidth(ellipsis);
            StringBuilder shortened = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                String next = shortened.toString() + text.charAt(i);
                if (g2d.getFontMetrics().stringWidth(next) + ellipsisWidth > maxWidth) {
                    break;
                }
                shortened.append(text.charAt(i));
            }
            return shortened + ellipsis;
        }
    }

    private static final class LeaderboardEntry {
        private final String name;
        private final int score;
        private final String status;

        private LeaderboardEntry(String name, int score, String status) {
            this.name = name;
            this.score = score;
            this.status = status;
        }
    }

    private static final class RenderPosition {
        private double x;
        private double y;

        private RenderPosition(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
