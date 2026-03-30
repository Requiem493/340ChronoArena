import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Scanner;

/**
 * TCP Client for Microservices Cluster
 * 
 * @author aditibaghel9, KFrancis05, help from claude.ai
 */
public class TCPClient {

    static String inputFileName = "";
    static String selectedOperation = "";

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java TCPClient <serverIP> <serverPort>");
            System.out.println("Example: java TCPClient 10.111.131.130 8000");
            return;
        }
        String serverIP = args[0];
        int serverPort = Integer.parseInt(args[1]);

        try {
            Socket socket = new Socket(serverIP, serverPort);
            socket.setKeepAlive(true);
            socket.setSoTimeout(0);
            socket.setSendBufferSize(65536);
            socket.setReceiveBufferSize(65536);
            socket.setTcpNoDelay(true);

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            Scanner scanner = new Scanner(System.in);

            System.out.println("Connected to Microservices Cluster");
            System.out.println("=====================================\n");

            out.write("LIST");
            out.newLine();
            out.flush();

            String serviceList = in.readLine();
            System.out.println("Available services: " + serviceList);

            while (true) {
                System.out.print("\nEnter service name(MOVE) or QUIT to exit: ");
                String service = scanner.nextLine().toUpperCase();

                if (service.equals("QUIT")) {
                    out.write("BYE");
                    out.newLine();
                    out.flush();
                    System.out.println("Goodbye!");
                    break;
                }

                String taskRequest;
                if (service.equals("MOVE")) {
                    System.out.println("Move service is currently unavailable. Please try again later.");
                    // taskRequest = handleMoveService(scanner);
                } else {
                    System.out.println("Unknown service, try again.");
                    continue;
                }
            }
            socket.close();
        } catch (IOException e) {
            System.out.println("Connection failed. Please check your connection and try again.");
        }
    }

}