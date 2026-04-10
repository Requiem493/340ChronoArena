package gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.net.DatagramPacket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import game.GameClient;
import game.GameClient.LocalGameState;
import game.Zone;

public class ArenaGUI extends JPanel {
    private static final int ARENA_WIDTH = 800;
    private static final int ARENA_HEIGHT = 600;
    private static final int GRID_SIZE = 40;
    private static final int INPUT_TICK_MS = 50;
    private static final int RENDER_TICK_MS = 16;
    private static final double POSITION_SMOOTHING = 0.35;

    private JLabel timerLabel;
    private JLabel scoreLabel;
    private JPanel arenaPanel;
    private final Set<Integer> pressedKeys = new HashSet<>();
    private final Map<String, RenderPosition> renderedPlayers = new HashMap<>();

    // Local UDP sender — no changes needed to GameClient
    private final AtomicLong seq = new AtomicLong(0);

    public ArenaGUI() {
        setLayout(new BorderLayout());
        buildHUD();
        buildArena();
        startRenderLoop();
    }

    public void focusArena() {
        arenaPanel.requestFocusInWindow();
    }

    private void sendMove(int dx, int dy) {
        sendAction("MOVE|" + dx + "|" + dy);
    }

    private void sendFreeze() {
        sendAction("FREEZE");
    }

    private void sendAction(String action) {
        if (GameClient.myPlayerID == null || GameClient.serverAddr == null || GameClient.udpSocket == null) {
            return;
        }
        try {
            String msg = seq.incrementAndGet() + "|" + GameClient.myPlayerID + "|" + action;
            byte[] data = msg.getBytes();
            DatagramPacket pkt = new DatagramPacket(data, data.length, GameClient.serverAddr, GameClient.udpPort);
            GameClient.udpSocket.send(pkt);
        } catch (IOException e) {
            System.err.println("[ArenaGUI] UDP send failed: " + e.getMessage());
        }
    }

    private void buildHUD() {
        timerLabel = new JLabel("Time: 00:00");
        timerLabel.setFont(new Font("DialogInput", Font.BOLD, 18));
        timerLabel.setForeground(Color.WHITE);
        scoreLabel = new JLabel("Score: 0");
        scoreLabel.setFont(new Font("DialogInput", Font.BOLD, 18));
        scoreLabel.setForeground(Color.WHITE);
        JPanel hud = new JPanel(new FlowLayout());
        hud.setBackground(new Color(40, 40, 40));
        hud.add(timerLabel);
        hud.add(scoreLabel);
        add(hud, BorderLayout.NORTH);
    }

    private void buildArena() {
        arenaPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                LocalGameState state = GameClient.localState;
                Graphics2D g2d = (Graphics2D) g.create();
                double scaleX = getWidth() / (double) ARENA_WIDTH;
                double scaleY = getHeight() / (double) ARENA_HEIGHT;

                // Background
                g2d.setColor(new Color(173, 216, 230));
                g2d.fillRect(0, 0, getWidth(), getHeight());

                g2d.scale(scaleX, scaleY);

                // Grid
                g2d.setColor(new Color(255, 255, 255, 80));
                for (int x = 0; x < ARENA_WIDTH; x += GRID_SIZE) g2d.drawLine(x, 0, x, ARENA_HEIGHT);
                for (int y = 0; y < ARENA_HEIGHT; y += GRID_SIZE) g2d.drawLine(0, y, ARENA_WIDTH, y);

                // Zones
                g2d.setStroke(new BasicStroke(3));
                for (LocalGameState.ZoneData z : state.zones) {
                    drawZone(g2d, z);
                }

                // Items (coins)
                for (LocalGameState.ItemData item : state.items) {
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

                // Players
                for (LocalGameState.PlayerData p : state.players.values()) {
                    RenderPosition renderPos = renderedPlayers.computeIfAbsent(
                            p.id,
                            ignored -> new RenderPosition(p.x, p.y));
                    int drawX = (int) Math.round(renderPos.x);
                    int drawY = (int) Math.round(renderPos.y);
                    boolean isMe = p.id.equals(GameClient.myPlayerID);
                    Color playerColor = isMe ? new Color(50, 150, 255) : new Color(220, 60, 60);
                    if (p.frozen) {
                        playerColor = new Color(140, 220, 255);
                    }
                    g2d.setColor(playerColor);
                    g2d.fillOval(drawX - 12, drawY - 12, 24, 24);
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(new Font("DialogInput", Font.BOLD, 10));
                    g2d.drawString(p.name, drawX - 15, drawY - 15);
                    g2d.drawString(p.score + "pts", drawX - 15, drawY + 28);
                    if (p.hasWeapon) {
                        g2d.drawString("Freeze", drawX - 15, drawY + 40);
                    }
                }
                g2d.dispose();
            }
        };

        arenaPanel.setPreferredSize(new Dimension(800, 600));
        arenaPanel.setFocusable(true);
        arenaPanel.setFocusTraversalKeysEnabled(false);
        arenaPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int keyCode = e.getKeyCode();
                boolean isNewPress = pressedKeys.add(keyCode);
                if (isNewPress && (keyCode == KeyEvent.VK_F || keyCode == KeyEvent.VK_SPACE)) {
                    sendFreeze();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                pressedKeys.remove(e.getKeyCode());
            }
        });

        Timer inputTimer = new Timer(INPUT_TICK_MS, e -> {
            int dx = 0;
            int dy = 0;

            if (pressedKeys.contains(KeyEvent.VK_W) || pressedKeys.contains(KeyEvent.VK_UP)) {
                dy -= 1;
            }
            if (pressedKeys.contains(KeyEvent.VK_S) || pressedKeys.contains(KeyEvent.VK_DOWN)) {
                dy += 1;
            }
            if (pressedKeys.contains(KeyEvent.VK_A) || pressedKeys.contains(KeyEvent.VK_LEFT)) {
                dx -= 1;
            }
            if (pressedKeys.contains(KeyEvent.VK_D) || pressedKeys.contains(KeyEvent.VK_RIGHT)) {
                dx += 1;
            }

            if (dx != 0 || dy != 0) {
                sendMove(dx, dy);
            }
        });
        inputTimer.start();

        arenaPanel.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                pressedKeys.clear();
            }
        });

        add(arenaPanel, BorderLayout.CENTER);
    }

    private void updateRenderedPlayers(LocalGameState state) {
        renderedPlayers.keySet().removeIf(playerId -> !state.players.containsKey(playerId));

        for (LocalGameState.PlayerData player : state.players.values()) {
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

    private void startRenderLoop() {
        Timer timer = new Timer(RENDER_TICK_MS, e -> {
            LocalGameState state = GameClient.localState;
            long secs = state.timeRemainingMs / 1000;
            timerLabel.setText(String.format("Time: %02d:%02d", secs / 60, secs % 60));

            String myScore = "0";
            if (GameClient.myPlayerID != null && state.players.containsKey(GameClient.myPlayerID)) {
                LocalGameState.PlayerData me = state.players.get(GameClient.myPlayerID);
                myScore = String.valueOf(me.score);
            }
            updateRenderedPlayers(state);
            scoreLabel.setText("Score: " + myScore);
            arenaPanel.repaint();
        });
        timer.start();
    }

    private static final class RenderPosition {
        double x;
        double y;

        RenderPosition(double x, double y) {
            this.x = x;
            this.y = y;
        }
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

    private void drawZone(Graphics2D g2d, LocalGameState.ZoneData z) {
        int[] pos = getZonePos(z.id);
        int x = pos[0];
        int y = pos[1];
        int width = 100;
        int height = 80;
        int inset = 4;

        Color zoneColor = getZoneColor(z.status);
        Color outlineColor = zoneColor.darker();
        Color baseFill = new Color(zoneColor.getRed(), zoneColor.getGreen(), zoneColor.getBlue(), 45);
        Color progressFill = new Color(zoneColor.getRed(), zoneColor.getGreen(), zoneColor.getBlue(), 130);

        g2d.setColor(baseFill);
        g2d.fillRect(x, y, width, height);

        int innerWidth = width - inset * 2;
        int innerHeight = height - inset * 2;
        double clampedPercent = Math.max(0.0, Math.min(1.0, z.capturePercent));
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
        g2d.drawString(z.id, x + 35, y + 25);
        g2d.setFont(new Font("DialogInput", Font.PLAIN, 10));
        g2d.drawString(z.status, x + 5, y + 45);

        if (!z.owner.equals("NONE")) {
            double secondsLeft = z.capturePercent * Zone.OWNERSHIP_DURATION_MS / 1000.0;
            g2d.drawString(z.owner, x + 5, y + 58);
            g2d.drawString(String.format("%.1fs left", secondsLeft), x + 5, y + 71);
        } else if (clampedPercent > 0.0) {
            g2d.drawString((int) Math.round(clampedPercent * 100) + "%", x + 5, y + 60);
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
}
