import java.io.*;
import java.net.*;
import java.util.*;

public class MainServer {
    private static HeartbeatReceiver heartbeatReceiver;
    
    public static void main(String[] args) {
        // Start UDP heartbeat receiver
        heartbeatReceiver = new HeartbeatReceiver();
        new Thread(heartbeatReceiver).start();
        System.out.println("Heartbeat receiver started on UDP 9999");
        
        // Start TCP server
        try (ServerSocket serverSocket = new ServerSocket(8000)) {
            System.out.println("Main Server started on TCP 8000");
            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());
                
                // CRITICAL: Spawn new thread for each client
                new Thread(new ClientHandler(clientSocket, heartbeatReceiver)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}