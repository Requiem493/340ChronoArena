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
import java.io.IOException;
import java.net.DatagramPacket;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import game.GameClient;
import game.GameClient.LocalGameState;

public class ArenaGUI extends JPanel {

    private JLabel timerLabel;
    private JLabel scoreLabel;
    private JPanel arenaPanel;
    private JButton upButton;
    private JButton downButton;
    private JButton leftButton;
    private JButton rightButton;
    private JButton freezeButton;
    private JButton dashButton;

    // Local UDP sender — no changes needed to GameClient
    private final AtomicLong seq = new AtomicLong(0);

    public ArenaGUI() {
        setLayout(new BorderLayout());
        buildHUD();
        buildArena();
        buildControls();
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

                // Background
                g.setColor(new Color(173, 216, 230));
                g.fillRect(0, 0, getWidth(), getHeight());

                // Grid
                g.setColor(new Color(255, 255, 255, 80));
                for (int x = 0; x < 800; x += 40) g.drawLine(x, 0, x, 600);
                for (int y = 0; y < 600; y += 40) g.drawLine(0, y, 800, y);

                // Zones
                Graphics2D g2d = (Graphics2D) g;
                g2d.setStroke(new BasicStroke(3));
                for (LocalGameState.ZoneData z : state.zones) {
                    int[] pos = getZonePos(z.id);
                    Color zoneColor = getZoneColor(z.status);
                    g2d.setColor(zoneColor);
                    g2d.fillRect(pos[0], pos[1], 100, 80);
                    g2d.setColor(zoneColor.darker());
                    g2d.drawRect(pos[0], pos[1], 100, 80);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("DialogInput", Font.BOLD, 12));
                    g.drawString(z.id, pos[0] + 35, pos[1] + 25);
                    g.setFont(new Font("DialogInput", Font.PLAIN, 10));
                    g.drawString(z.status, pos[0] + 5, pos[1] + 45);
                    if (!z.owner.equals("NONE")) {
                        g.drawString(z.owner, pos[0] + 5, pos[1] + 60);
                    }
                }

                // Items (coins)
                for (LocalGameState.ItemData item : state.items) {
                    if ("FREEZE_RAY".equals(item.type)) {
                        g.setColor(new Color(120, 240, 255));
                        g.fillRect(item.x - 10, item.y - 10, 20, 20);
                        g2d.setColor(new Color(40, 120, 180));
                        g2d.setStroke(new BasicStroke(2));
                        g2d.drawRect(item.x - 10, item.y - 10, 20, 20);
                    } else {
                        g.setColor(new Color(255, 215, 0));
                        g.fillOval(item.x - 8, item.y - 8, 16, 16);
                        g2d.setColor(new Color(200, 160, 0));
                        g2d.setStroke(new BasicStroke(2));
                        g2d.drawOval(item.x - 8, item.y - 8, 16, 16);
                    }
                }

                // Players
                for (LocalGameState.PlayerData p : state.players.values()) {
                    boolean isMe = p.id.equals(GameClient.myPlayerID);
                    Color playerColor = isMe ? new Color(50, 150, 255) : new Color(220, 60, 60);
                    if (p.frozen) {
                        playerColor = new Color(140, 220, 255);
                    }
                    g.setColor(playerColor);
                    g.fillOval(p.x - 12, p.y - 12, 24, 24);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("DialogInput", Font.BOLD, 10));
                    g.drawString(p.name, p.x - 15, p.y - 15);
                    g.drawString(p.score + "pts", p.x - 15, p.y + 28);
                    if (p.hasWeapon) {
                        g.drawString("Freeze", p.x - 15, p.y + 40);
                    }
                }
            }
        };

        arenaPanel.setPreferredSize(new Dimension(800, 600));
        arenaPanel.setFocusable(true);
        arenaPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W, KeyEvent.VK_UP -> sendMove(0, -1);
                    case KeyEvent.VK_S, KeyEvent.VK_DOWN -> sendMove(0, 1);
                    case KeyEvent.VK_A, KeyEvent.VK_LEFT -> sendMove(-1, 0);
                    case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> sendMove(1, 0);
                    case KeyEvent.VK_F, KeyEvent.VK_SPACE -> sendFreeze();
                }
            }
        });

        add(arenaPanel, BorderLayout.CENTER);
    }

    private void buildControls() {
        upButton = new JButton("UP");
        downButton = new JButton("DOWN");
        leftButton = new JButton("LEFT");
        rightButton = new JButton("RIGHT");
        freezeButton = new JButton("FREEZE");
        dashButton = new JButton("DASH");

        upButton.addActionListener(e -> sendMove(0, -1));
        downButton.addActionListener(e -> sendMove(0, 1));
        leftButton.addActionListener(e -> sendMove(-1, 0));
        rightButton.addActionListener(e -> sendMove(1, 0));
        freezeButton.addActionListener(e -> sendFreeze());

        JPanel controls = new JPanel(new FlowLayout());
        controls.setBackground(new Color(40, 40, 40));
        controls.add(leftButton);
        controls.add(upButton);
        controls.add(downButton);
        controls.add(rightButton);
        controls.add(freezeButton);
        controls.add(dashButton);
        add(controls, BorderLayout.SOUTH);
    }

    private void startRenderLoop() {
        Timer timer = new Timer(50, e -> {
            LocalGameState state = GameClient.localState;
            long secs = state.timeRemainingMs / 1000;
            timerLabel.setText(String.format("Time: %02d:%02d", secs / 60, secs % 60));

            String myScore = "0";
            if (GameClient.myPlayerID != null && state.players.containsKey(GameClient.myPlayerID)) {
                LocalGameState.PlayerData me = state.players.get(GameClient.myPlayerID);
                myScore = String.valueOf(me.score);
                freezeButton.setEnabled(me.hasWeapon);
                freezeButton.setText(me.hasWeapon ? "FREEZE READY" : "FREEZE");
            } else {
                freezeButton.setEnabled(false);
                freezeButton.setText("FREEZE");
            }
            scoreLabel.setText("Score: " + myScore);
            arenaPanel.repaint();
        });
        timer.start();
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

    private Color getZoneColor(String status) {
        return switch (status) {
            case "CAPTURED" -> new Color(100, 200, 100, 100);
            case "CAPTURING" -> new Color(100, 150, 255, 100);
            case "CONTESTED" -> new Color(255, 165, 0, 100);
            default -> new Color(200, 200, 200, 80);
        };
    }
}
