package referencefiles;

import java.net.*;
import java.util.*;

/**
 * @author KFrancis05, help from claude.ai
 */
public class HeartbeatReceiver implements Runnable {
    private DatagramSocket socket;
    private volatile boolean running = true;
    private Map<String, NodeInfo> nodes = new HashMap<>();

    public HeartbeatReceiver() {
        try {
            socket = new DatagramSocket(9999);
            socket.setSoTimeout(5000); // wake up every 5 seconds to check for dead nodes
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[1024];
        long lastCheck = System.currentTimeMillis();

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    String[] parts = message.split("\\|");
                    if (parts.length == 4 && parts[0].equals("HEARTBEAT")) {
                        String nodeName = parts[1];
                        String serviceName = parts[2];
                        int tcpPort = Integer.parseInt(parts[3]);
                        String nodeIP = packet.getAddress().getHostAddress();
                        NodeInfo info = new NodeInfo(nodeName, serviceName, nodeIP, tcpPort);
                        nodes.put(nodeName, info);
                        System.out.println("Heartbeat received: " + info);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // No packet received, just continue to dead node check
                }

                // Check for dead nodes every 30 seconds
                if ((System.currentTimeMillis() - lastCheck) > 30000) {
                    checkForDeadNodes();
                    lastCheck = System.currentTimeMillis();
                }

            } catch (Exception e) {
                if (running)
                    e.printStackTrace();
            }
        }
    }

    public void checkForDeadNodes() {
        long currentTime = System.currentTimeMillis();
        Iterator<Map.Entry<String, NodeInfo>> iterator = nodes.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, NodeInfo> entry = iterator.next();
            NodeInfo info = entry.getValue();
            long secondsSinceLastSeen = (currentTime - info.lastSeen) / 1000;

            if (secondsSinceLastSeen > 120) {
                System.out
                        .println("Node DEAD: " + info.nodeName + " (silent for " + secondsSinceLastSeen + " seconds)");
                iterator.remove();
            }
        }
    }

    public List<String> getAliveServices() {
        List<String> services = new ArrayList<>();
        for (NodeInfo info : nodes.values()) {
            services.add(info.serviceName);
        }
        return services;
    }

    public NodeInfo getNodeInfoByService(String serviceName) {
        for (NodeInfo info : nodes.values()) {
            if (info.serviceName.equalsIgnoreCase(serviceName)) {
                return info;
            }
        }
        return null;
    }

    public void stop() {
        running = false;
        socket.close();
    }
}