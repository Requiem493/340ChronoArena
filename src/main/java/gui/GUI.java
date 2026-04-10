package gui;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import game.GameClient;

public class GUI {

    private JFrame frame;
    private JButton startButton;
    private JButton howtoButton;

    public GUI() {
        
        frame = new JFrame("Chrono Arena");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 800);

        JLabel title = new JLabel("Welcome to Chrono Arena!", JLabel.CENTER);
        title.setFont(new Font("DialogInput", Font.BOLD, 50));

        JLabel nameLabel, ipLabel;
        
        JTextField textBox = new JTextField(20);
        textBox.setBounds(495,250,200,50);
        frame.add(textBox);

        nameLabel = new JLabel("Name");
        nameLabel.setBounds(150, 215, 100, 350);
        frame.add(nameLabel);

        JPasswordField IPtextBox = new JPasswordField(20);
        IPtextBox.setBounds(495,300,200,50);
        frame.add(IPtextBox);

        ipLabel = new JLabel("IP");
        ipLabel.setBounds(415, 215, 100, 350);
        frame.add(ipLabel);

        //GameClient.serverIP = IP;

        startButton = new JButton("Start Game");
        startButton.setFont(new Font("DialogInput", Font.BOLD, 24));
        startButton.setPreferredSize(new Dimension(300, 80));

        howtoButton = new JButton("How To Play");
        howtoButton.setFont(new Font("DialogInput", Font.BOLD, 24));
        howtoButton.setPreferredSize(new Dimension(300, 80));

        // Style the button
        startButton.setBackground(new Color(40, 40, 40));
        startButton.setForeground(Color.BLACK);
        startButton.setFocusPainted(false);
        startButton.setBorderPainted(true);
        startButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        howtoButton.setBackground(new Color(40, 40, 40));
        howtoButton.setForeground(Color.BLACK);
        howtoButton.setFocusPainted(false);
        howtoButton.setBorderPainted(true);
        howtoButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        // Center the button
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(140, 184, 255));
        panel.add(textBox);
        panel.add(IPtextBox);
        panel.add(startButton);
        panel.add(howtoButton);

        frame.add(title, BorderLayout.NORTH);
        frame.add(panel, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null); // centers window on screen
        GameClient.loadProperties(); // loads serverIP from file
        IPtextBox.setText(GameClient.serverIP); // pre-fills the box
        frame.setVisible(true);

        startButton.addActionListener(new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent event) {

            String playerInput = textBox.getText().trim();
            String IP = new String(IPtextBox.getPassword()).trim();

            if (playerInput.isEmpty() || IP.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Please fill in all fields!");
                return;
            }
            else{
                GameClient.name = playerInput;
                String finalIP = IP;
                startButton.setEnabled(false);
                new Thread(() -> {
                    try {
                        GameClient.connect(finalIP);
                        SwingUtilities.invokeLater(GUI.this::showArenaScreen);
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(null, "Could not connect!");
                            startButton.setEnabled(true);
                        });
                    }
                }).start(); 
            }
        }
        });  

        howtoButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
    
                JOptionPane.showMessageDialog(null, "Movements:\nW/Up Arrow: Up\nA/Left Arrow: Left\nS/Down Arrow: Down\nD/Right Arrow: Right\nWeapons:\nFreeze Ray: Freezes nearest player");
                    return;
            }
            });
    }

    private void showArenaScreen() {
        ArenaGUI arenaGUI = new ArenaGUI();
        frame.setContentPane(arenaGUI);
        frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        frame.revalidate();
        frame.repaint();
        arenaGUI.focusArena();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(GUI::new);
    }
}