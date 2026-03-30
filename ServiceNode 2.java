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

    private CSVStatsService csvService;
    private ImageTransformService imageService;

    public ServiceNode(String serviceName, int tcpPort, String serverIP, String nodeIP) {
        this.serviceName = serviceName.toUpperCase();
        this.tcpPort = tcpPort;
        this.serverIP = serverIP;
        this.nodeIP = nodeIP;
        this.csvService = new CSVStatsService();
        this.imageService = new ImageTransformService();
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
            serverIP
        );
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
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
             BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream()))) {

            String result;

            if (serviceName.equals("CSV")) {
                System.out.println(">>> Processing CSV data (streaming)...");
                result = "SUCCESS|" + csvService.processCSVFromSocket(in);
                System.out.println(">>> CSV processing complete");
            } else {
                String taskData = in.readLine();

                if (taskData == null || taskData.isEmpty()) {
                    out.write("ERROR|No task data received");
                    out.newLine();
                    out.flush();
                    return;
                }

                String displayData = taskData.length() > 100
                    ? taskData.substring(0, 100) + "..."
                    : taskData;
                System.out.println(">>> Received task data: " + displayData);

                switch (serviceName) {
                    case "IMAGE":
                        result = processImage(taskData);
                        break;
                    case "BASE64":
                        result = processBase64(taskData);
                        break;
                    case "HMAC":
                        result = processHMAC(taskData);
                        break;
                    case "COMPRESSION":
                        result = processCompression(taskData);
                        break;
                    default:
                        result = "ERROR|Unknown service: " + serviceName;
                }
            }

            out.write(result);
            out.newLine();
            out.flush();
            System.out.println(">>> Task completed, result sent\n");

        } catch (IOException e) {
            System.err.println("ERROR handling task: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String processImage(String taskData) {
        try {
            System.out.println(">>> Processing IMAGE task...");
            String result = imageService.process(taskData);
            System.out.println(">>> IMAGE processing complete");
            return result;
        } catch (Exception e) {
            return "ERROR|Image processing failed: " + e.getMessage();
        }
    }

    private String processBase64(String taskData) {
        try {
            System.out.println(">>> Processing BASE64 task...");
            String[] parts = taskData.split("\\|", 2);
            String operation = parts[0];
            String data = parts.length > 1 ? parts[1] : "";

            if (operation.equalsIgnoreCase("ENCODE")) {
                if (data.startsWith("BINARY:")) {
                    String encoded = data.substring(7);
                    return "SUCCESS|" + encoded;
                } else {
                    String encoded = java.util.Base64.getEncoder().encodeToString(data.getBytes());
                    return "SUCCESS|" + encoded;
                }
            } else if (operation.equalsIgnoreCase("DECODE")) {
                data = data.replace("\\n", "\n");
                byte[] decoded = java.util.Base64.getDecoder().decode(data.trim());
                return "SUCCESS|" + java.util.Base64.getEncoder().encodeToString(decoded);
            } else {
                return "ERROR|Unknown BASE64 operation: " + operation;
            }
        } catch (Exception e) {
            return "ERROR|Base64 processing failed: " + e.getMessage();
        }
    }

    private String processHMAC(String taskData) {
        try {
            System.out.println(">>> Processing HMAC task...");
            String[] parts = taskData.split("\\|");
            if (parts.length < 3) {
                return "ERROR|HMAC requires: SIGN|message|key or VERIFY|message|key|signature";
            }

            String operation = parts[0];
            String message = parts[1];
            String secretKey = parts[2];

            byte[] messageBytes;
            if (message.startsWith("BINARY:")) {
                messageBytes = java.util.Base64.getDecoder().decode(message.substring(7));
            } else {
                message = message.replace("\\n", "\n");
                messageBytes = message.getBytes();
            }

            if (operation.equalsIgnoreCase("SIGN")) {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                javax.crypto.spec.SecretKeySpec keySpec =
                    new javax.crypto.spec.SecretKeySpec(secretKey.getBytes(), "HmacSHA256");
                mac.init(keySpec);
                byte[] rawSignature = mac.doFinal(messageBytes);
                StringBuilder hex = new StringBuilder();
                for (byte b : rawSignature) {
                    hex.append(String.format("%02x", b));
                }
                return "SUCCESS|" + hex.toString();

            } else if (operation.equalsIgnoreCase("VERIFY")) {
                if (parts.length < 4) {
                    return "ERROR|VERIFY requires signature";
                }
                String providedSignature = parts[3];
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                javax.crypto.spec.SecretKeySpec keySpec =
                    new javax.crypto.spec.SecretKeySpec(secretKey.getBytes(), "HmacSHA256");
                mac.init(keySpec);
                byte[] rawSignature = mac.doFinal(messageBytes);
                StringBuilder hex = new StringBuilder();
                for (byte b : rawSignature) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString().equals(providedSignature) ? "SUCCESS|VALID" : "SUCCESS|INVALID";
            } else {
                return "ERROR|Unknown HMAC operation: " + operation;
            }
        } catch (Exception e) {
            return "ERROR|HMAC processing failed: " + e.getMessage();
        }
    }

    private String processCompression(String taskData) {
        try {
            System.out.println(">>> Processing COMPRESSION task...");
            String[] parts = taskData.split("\\|", 2);
            String operation = parts[0];
            String input = parts.length > 1 ? parts[1] : "";

            if (input.isEmpty()) {
                return "ERROR|No input provided";
            }

            if (operation.equalsIgnoreCase("COMPRESS")) {
                byte[] bytes;
                if (input.startsWith("BINARY:")) {
                    // Binary file sent as Base64
                    bytes = java.util.Base64.getDecoder().decode(input.substring(7));
                } else {
                    // Plain text
                    input = input.replace("\\n", "\n");
                    bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                java.io.ByteArrayOutputStream byteStream = new java.io.ByteArrayOutputStream();
                java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(byteStream);
                gzip.write(bytes);
                gzip.close();
                byte[] compressedBytes = byteStream.toByteArray();
                String result = java.util.Base64.getEncoder().encodeToString(compressedBytes);
                System.out.println(">>> Compression complete (ratio: " +
                    String.format("%.1f%%", (1.0 - (double) compressedBytes.length / bytes.length) * 100) + ")");
                return "SUCCESS|" + result;

            } else if (operation.equalsIgnoreCase("DECOMPRESS")) {
                byte[] bytes = java.util.Base64.getDecoder().decode(input);
                java.io.ByteArrayInputStream byteStream = new java.io.ByteArrayInputStream(bytes);
                java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(byteStream);
                java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = gzip.read(buffer)) > -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                gzip.close();
                // Return as Base64 so binary data survives transport
                String result = java.util.Base64.getEncoder().encodeToString(outputStream.toByteArray());
                System.out.println(">>> Decompression complete");
                return "SUCCESS|" + result;

            } else {
                return "ERROR|Unknown COMPRESSION operation: " + operation;
            }
        } catch (Exception e) {
            return "ERROR|Compression processing failed: " + e.getMessage();
        }
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: java ServiceNode <serviceName> <tcpPort> <serverIP> <nodeIP>");
            System.out.println("Example: java ServiceNode CSV 8010 192.168.56.1 192.168.64.5");
            System.out.println("\nAvailable services:");
            System.out.println("  CSV          - Calculate statistics on CSV data");
            System.out.println("  IMAGE        - Resize, rotate, grayscale images");
            System.out.println("  BASE64       - Encode/decode Base64");
            System.out.println("  HMAC         - Sign/verify messages");
            System.out.println("  COMPRESSION  - Compress/decompress text");
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