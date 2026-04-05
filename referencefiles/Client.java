package referencefiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QU Micro-services Cluster — Interactive Client
 *
 * _FILE actions mean:
 * - read file from client machine
 * - send content to server
 * - receive processed content/result back
 * - save output locally in ./outfiles
 */
public class Client {

    private static final int TIMEOUT_MS = 120_000;
    private static final AtomicInteger REQ_ID = new AtomicInteger(1);

    // Local output folder relative to wherever client is run from
    private static final Path CLIENT_OUTPUT_DIR = Path.of(System.getProperty("user.dir"), "outfiles");

    private final String routerIP;
    private final int routerPort;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    public Client(String routerIP, int routerPort) throws IOException {
        this.routerIP = routerIP;
        this.routerPort = routerPort;
        connect();
    }

    private void connect() throws IOException {
        socket = new Socket(routerIP, routerPort);
        socket.setSoTimeout(TIMEOUT_MS);

        out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        Files.createDirectories(CLIENT_OUTPUT_DIR);

        System.out.println("[Client] Connected");
        System.out.println("[Client] Local outputs will be saved in: " + CLIENT_OUTPUT_DIR.toAbsolutePath());
    }

    private JSONObject sendRequest(JSONObject request) throws IOException {
        out.println(request.toString());

        try {
            String raw = in.readLine();
            if (raw == null) {
                throw new IOException("Router closed the connection.");
            }
            return new JSONObject(raw);
        } catch (SocketTimeoutException e) {
            throw new IOException("Router did not respond within " + (TIMEOUT_MS / 1000) + "s.");
        }
    }

    public void listServices() {
        try {
            JSONObject req = new JSONObject();
            req.put("type", "LIST_SERVICES");

            JSONObject resp = sendRequest(req);

            JSONArray services = resp.optJSONArray("services");
            if (services == null || services.length() == 0) {
                System.out.println("  (no services currently available)");
            } else {
                System.out.println("\nAvailable services:");
                for (int i = 0; i < services.length(); i++) {
                    System.out.println("  [" + (i + 1) + "] " + services.getString(i));
                }
            }
        } catch (IOException e) {
            System.err.println("ERROR listing services: " + e.getMessage());
        }
    }

    public void invokeService(String service, String action, String payload) {
        try {
            int id = REQ_ID.getAndIncrement();

            JSONObject req = new JSONObject();
            req.put("type", "SERVICE_REQUEST");
            req.put("requestId", id);
            req.put("service", service.toUpperCase().trim());
            req.put("action", action == null || action.isBlank() ? "DEFAULT" : action.toUpperCase().trim());
            req.put("payload", payload);

            System.out.println("\nSending request #" + id);
            JSONObject resp = sendRequest(req);

            boolean success = resp.optBoolean("success", false);
            if (!success) {
                System.out.println("Error: " + resp.optString("error", "Unknown error"));
                return;
            }

            String result = resp.optString("result", "(empty)");

            // If service returned structured JSON for local file save
            if (looksLikeJson(result)) {
                JSONObject resultJson = new JSONObject(result);
                String status = resultJson.optString("status", "");

                if ("FILE_SAVED".equals(status)) {
                    String outputFileName = resultJson.getString("outputFileName");
                    String outputContentBase64 = resultJson.getString("outputContentBase64");

                    Path outputPath = CLIENT_OUTPUT_DIR.resolve(outputFileName);
                    saveBase64ToFile(outputContentBase64, outputPath);

                    System.out.println("Saved locally: " + outputPath.toAbsolutePath());
                    return;
                }
            }

            System.out.println("Result:\n" + result);

        } catch (IOException e) {
            System.err.println("ERROR invoking service: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                JSONObject disconnect = new JSONObject();
                disconnect.put("type", "DISCONNECT");
                out.println(disconnect.toString());
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void printBanner() {
        System.out.println("-----------------------------------------------");
        System.out.println("|      Micro-services Cluster Client           |");
        System.out.println("-----------------------------------------------");
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("  1) List available services");
        System.out.println("  2) Invoke a service");
        System.out.println("  3) Test all services");
        System.out.println("  0) Quit");
        System.out.print("  Choose: ");
    }

    private static void interactiveInvoke(Client client, Scanner sc) {
        System.out.print("Service name (e.g. BASE64, GZIP, HMAC, CSV, ENTROPY): ");
        String service = sc.nextLine().trim().toUpperCase();

        String action = selectActionForService(service, sc);
        if (action == null) {
            System.out.println("Invalid service or action selection.");
            return;
        }

        boolean fileAction = action.equals("STATS_FILE") ||
                action.equals("ENCODE_FILE") ||
                action.equals("DECODE_FILE") ||
                action.equals("COMPRESS_FILE") ||
                action.equals("DECOMPRESS_FILE") ||
                action.equals("SIGN_FILE") ||
                action.equals("VERIFY_FILE") ||
                action.equals("ANALYZE_FILE");

        String payload;

        try {
            if (fileAction) {
                System.out.print("Local file path on this laptop: ");
                String localFilePath = sc.nextLine().trim();
                validateFileSize(localFilePath);

                String fileName = Path.of(localFilePath).getFileName().toString();
                String fileContentBase64 = readFileAsBase64(localFilePath);

                JSONObject filePayload = new JSONObject();
                filePayload.put("fileName", fileName);
                filePayload.put("fileContentBase64", fileContentBase64);

                if ("HMAC".equals(service)) {
                    System.out.print("Enter HMAC key: ");
                    String key = sc.nextLine().trim();
                    filePayload.put("key", key);

                    if ("VERIFY_FILE".equals(action)) {
                        System.out.print("Enter expected tag (Base64): ");
                        String tag = sc.nextLine().trim();
                        filePayload.put("tag", tag);
                    }
                }

                payload = filePayload.toString();

            } else {
                System.out.println("Input mode:");
                System.out.println("  1) Manual input");
                System.out.println("  2) File input");
                System.out.print("Choose input mode: ");
                String mode = sc.nextLine().trim();

                if ("2".equals(mode)) {
                    System.out.print("Local file path to read now: ");
                    String localFilePath = sc.nextLine().trim();
                    validateFileSize(localFilePath);

                    switch (service) {
                        case "CSV":
                            payload = readTextFile(localFilePath);
                            break;

                        case "BASE64":
                            payload = readTextFile(localFilePath);
                            break;

                        case "GZIP":
                        case "ENTROPY":
                            payload = readFileAsBase64(localFilePath);
                            break;

                        case "HMAC":
                            System.out.print("Enter HMAC key: ");
                            String key = sc.nextLine().trim();
                            String keyB64 = Base64.getEncoder()
                                    .encodeToString(key.getBytes(StandardCharsets.UTF_8));
                            String fileB64 = readFileAsBase64(localFilePath);
                            payload = keyB64 + "|" + fileB64;
                            break;

                        default:
                            payload = readTextFile(localFilePath);
                            break;
                    }
                } else {
                    System.out.print("Payload: ");
                    payload = sc.nextLine();
                    payload = payload.replace("\\n", "\n");
                }
            }

            client.invokeService(service, action, payload);

        } catch (IOException e) {
            System.err.println("ERROR reading file: " + e.getMessage());
        }
    }

    private static String selectActionForService(String service, Scanner sc) {
        String[] actions;

        switch (service) {
            case "BASE64":
                actions = new String[] { "ENCODE", "DECODE", "ENCODE_FILE", "DECODE_FILE" };
                break;
            case "GZIP":
                actions = new String[] { "COMPRESS", "DECOMPRESS", "COMPRESS_FILE", "DECOMPRESS_FILE" };
                break;
            case "HMAC":
                actions = new String[] { "SIGN", "VERIFY", "SIGN_FILE", "VERIFY_FILE" };
                break;
            case "CSV":
                actions = new String[] { "STATS", "STATS_FILE" };
                break;
            case "ENTROPY":
                actions = new String[] { "ANALYZE", "ANALYZE_FILE" };
                break;
            default:
                return null;
        }

        System.out.println("Available actions for " + service + ":");
        for (int i = 0; i < actions.length; i++) {
            System.out.println("  " + (i + 1) + ") " + actions[i]);
        }
        System.out.print("Choose action number: ");

        String choice = sc.nextLine().trim();
        try {
            int idx = Integer.parseInt(choice);
            if (idx >= 1 && idx <= actions.length) {
                return actions[idx - 1];
            }
        } catch (NumberFormatException ignored) {
        }

        return null;
    }

    private static void runDemos(Client client) {
        System.out.println("\n--- Demo 1: BASE64 ENCODE ---");
        client.invokeService("BASE64", "ENCODE", "Hello from QU Cluster!");

        System.out.println("\n--- Demo 2: BASE64 DECODE ---");
        client.invokeService("BASE64", "DECODE", "SGVsbG8gZnJvbSBRVSBDbHVzdGVyIQ==");

        System.out.println("\n--- Demo 3: CSV STATS ---");
        String csv = "age,score\n22,88\n25,92\n30,76\n28,95\n21,83";
        client.invokeService("CSV", "STATS", csv);

        System.out.println("\n--- Demo 4: ENTROPY ANALYZE ---");
        client.invokeService("ENTROPY", "ANALYZE", "SGVsbG8gV29ybGQ=");

        System.out.println("\n--- Demo 5: GZIP COMPRESS ---");
        String b64Input = Base64.getEncoder()
                .encodeToString("compress me please".getBytes(StandardCharsets.UTF_8));
        client.invokeService("GZIP", "COMPRESS", b64Input);

        System.out.println("\n--- Demo 6: HMAC SIGN ---");
        String key = Base64.getEncoder().encodeToString("secret".getBytes(StandardCharsets.UTF_8));
        String msg = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        client.invokeService("HMAC", "SIGN", key + "|" + msg);
    }

    private static void pause(Scanner sc) {
        System.out.print("\nPress Enter to continue...");
        sc.nextLine();
    }

    private static String readTextFile(String filePath) throws IOException {
        return Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
    }

    private static String readFileAsBase64(String filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of(filePath));
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static void saveBase64ToFile(String base64, Path outputPath) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        Files.write(outputPath, bytes);
    }

    private static boolean looksLikeJson(String s) {
        if (s == null)
            return false;
        String t = s.trim();
        return t.startsWith("{") && t.endsWith("}");
    }

    private static void validateFileSize(String filePath) throws IOException {
        long size = Files.size(Path.of(filePath));

        System.out.println("File size: " + size + " bytes");

        if (size > 20L * 1024 * 1024) {
            System.out.println("WARNING: Larger file detected. For client-upload mode, keep files moderate.");
        } else if (size > 5L * 1024 * 1024) {
            System.out.println("NOTICE: Medium file detected.");
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java Client <routerIP> <routerTCPPort>");
            System.out.println("Example: java Client 127.0.0.1 5050");
            System.exit(1);
        }

        String routerIP = args[0];
        int routerPort;

        try {
            routerPort = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + args[1]);
            System.exit(1);
            return;
        }

        printBanner();

        Client client;
        try {
            client = new Client(routerIP, routerPort);
        } catch (IOException e) {
            System.err.println("Connection Failed");
            System.exit(1);
            return;
        }

        Scanner sc = new Scanner(System.in);
        boolean running = true;

        while (running) {
            printMenu();
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    client.listServices();
                    pause(sc);
                    break;

                case "2":
                    interactiveInvoke(client, sc);
                    pause(sc);
                    break;

                case "3":
                    runDemos(client);
                    pause(sc);
                    break;

                case "0":
                    running = false;
                    break;

                default:
                    System.out.println("Unknown option — try 0, 1, 2, or 3.");
                    pause(sc);
            }
        }

        client.close();
        System.out.println("Goodbye!");
    }
}