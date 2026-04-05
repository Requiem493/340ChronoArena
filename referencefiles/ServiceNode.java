package referencefiles;

import java.io.*;
import java.net.*;

/**
 * @author aditibaghel9, KFrancis05, help from claude.ai
 */
public class ServiceNode {
    private String serviceName;
    private int tcpPort;
    private String serverIP;
    private String nodeIP;

    public ServiceNode(String serviceName, int tcpPort, String serverIP, String nodeIP) {
        this.serviceName = serviceName.toUpperCase();
        this.tcpPort = tcpPort;
        this.serverIP = serverIP;
        this.nodeIP = nodeIP;
    }

    public void start() {
        System.out.println("=".repeat(60));
        System.out.println("SERVICE NODE: " + serviceName);
        System.out.println("TCP Port: " + tcpPort);
        System.out.println("Server IP: " + serverIP);
        System.out.println("Node IP: " + nodeIP);
        System.out.println("=".repeat(60));

        HeartbeatSender heartbeat = new HeartbeatSender(
                "node-" + serviceName,
                serviceName,
                tcpPort,
                serverIP);
        new Thread(heartbeat).start();
        System.out.println("✓ Heartbeat sender started (sending to " + serverIP + ":9999)");

        try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
            System.out.println("✓ Listening for tasks on port " + tcpPort);
            System.out.println("✓ Service Node ready!\n");

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println(">>> Main Server connected for task");
                new Thread(() -> handleTask(socket)).start();
            }
        } catch (IOException e) {
            System.err.println("ERROR: Failed to start TCP server on port " + tcpPort);
            e.printStackTrace();
        }
    }

    private void handleTask(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            String result;

            out.write("Hi");
            out.newLine();
            out.flush();
            System.out.println(">>> Task completed, result sent\n");
        } catch (IOException e) {
            System.err.println("ERROR handling task: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: java ServiceNode <serviceName> <tcpPort> <serverIP> <nodeIP>");
            System.out.println("Example: java ServiceNode MOVE 8010 192.168.56.1 192.168.64.5");
            System.out.println("\nAvailable services:");
            System.out.println("  MOVE          - Move to new position");
            return;
        }

        String serviceName = args[0];
        int tcpPort = Integer.parseInt(args[1]);
        String serverIP = args[2];
        String nodeIP = args[3];

        ServiceNode node = new ServiceNode(serviceName, tcpPort, serverIP, nodeIP);
        node.start();
    }
}