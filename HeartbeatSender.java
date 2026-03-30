

import java.net.*;
import java.util.Random;
/**
 * 
 * @author claude.ai
 *
 *  
 * 
 */
public class HeartbeatSender implements Runnable {
    
    private String nodeName;
    private String serviceName;
    private int tcpPort;
    private String serverIP;
    private static final int SERVER_UDP_PORT = 9999;
    private volatile boolean running = true;

    public HeartbeatSender(String nodeName, String serviceName, int tcpPort, String serverIP) {
        this.nodeName = nodeName;
        this.serviceName = serviceName;
        this.tcpPort = tcpPort;
        this.serverIP = serverIP;
    }

    @Override
    public void run() {
        Random random = new Random();
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress serverAddress = InetAddress.getByName(serverIP);

            while (running) {
                // Build the heartbeat message
                String message = "HEARTBEAT|" + nodeName + "|" + serviceName + "|" + tcpPort;
                byte[] data = message.getBytes();

                // Send it to the server
                DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, SERVER_UDP_PORT);
                socket.send(packet);
                System.out.println("Heartbeat sent: " + message);

                // Wait random 15-30 seconds before next heartbeat
                int delay = 15000 + random.nextInt(15001);
                Thread.sleep(delay);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        running = false;
    }
}