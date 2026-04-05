package game;

import java.net.*;

/**
 * UDPActionReceiver - Listens for player action packets on a UDP port.
 *
 * Each UDP packet from a game client has the format:
 * <seq>|<playerID>|<ACTION>|<params...>
 *
 * Examples:
 * 42|P1|MOVE|1|0 → P1 moves right
 * 43|P1|MOVE|-1|1 → P1 moves left+down
 *
 * This class simply receives packets and hands them to
 * GameState.enqueueAction()
 * which handles sequence-number deduplication and out-of-order dropping.
 *
 * @author aditibaghel9, KFrancis05, help from claude.ai
 */
public class UDPReceiver implements Runnable {

    private GameState gameState;
    private int port;
    private volatile boolean running = true;

    private static final int BUFFER_SIZE = 512;

    public UDPReceiver(GameState gameState, int port) {
        this.gameState = gameState;
        this.port = port;
    }

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(1000); // wake up every second to check `running`
            byte[] buffer = new byte[BUFFER_SIZE];

            System.out.println("[UDP] Action receiver listening on port " + port);

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String raw = new String(packet.getData(), 0, packet.getLength()).trim();

                    // Basic sanity check before handing off
                    if (!raw.isEmpty()) {
                        gameState.enqueueAction(raw);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // Normal — loop to re-check `running`
                }
            }
        } catch (Exception e) {
            if (running)
                System.err.println("[UDP] Error: " + e.getMessage());
        }

        System.out.println("[UDP] Action receiver stopped.");
    }

    public void stop() {
        running = false;
    }
}