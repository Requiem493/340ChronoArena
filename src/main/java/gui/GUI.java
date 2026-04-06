package gui;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class GUI {

    private JFrame frame;
    private JButton startButton;

    public GUI() {
        frame = new JFrame("Chrono Arena");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 800);

        JLabel title = new JLabel("Welcome to Chrono Arena!", JLabel.CENTER);
        title.setFont(new Font("DialogInput", Font.BOLD, 50));

        startButton = new JButton("Start Game");
        startButton.setFont(new Font("DialogInput", Font.BOLD, 24));
        startButton.setPreferredSize(new Dimension(300, 80));

        // Style the button
        startButton.setBackground(new Color(40, 40, 40));
        startButton.setForeground(Color.BLACK);
        startButton.setFocusPainted(false);
        startButton.setBorderPainted(true);
        startButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        // Center the button
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(140, 184, 255));
        panel.add(startButton);

        frame.add(title, BorderLayout.NORTH);
        frame.add(panel, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null); // centers window on screen
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(GUI::new);
    }
}