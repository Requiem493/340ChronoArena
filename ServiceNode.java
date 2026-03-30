package edu.qu.microcluster.core;

import edu.qu.microcluster.server.ServiceFactory;
import edu.qu.microcluster.services.Service;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class ServiceNode {

    static class ServiceRequestHandler extends Thread {
        private final int port;
        private final String serviceName;

        public ServiceRequestHandler(int port, String serviceName) {
            this.port = port;
            this.serviceName = serviceName;
        }

        @Override
        public void run() {
            try {
                ServerSocket serverSocket = new ServerSocket(port);
                System.out.println(serviceName + " TCP server listening on port " + port);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleRequest(clientSocket)).start();
                }

            } catch (Exception e) {
                System.err.println("ServiceRequestHandler error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void handleRequest(Socket socket) {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

                String requestLine = in.readLine();
                System.out.println("Received: " + requestLine);

                if (requestLine != null) {
                    JSONObject req = new JSONObject(requestLine);
                    JSONObject resp = processServiceRequest(req);
                    out.println(resp.toString());
                    System.out.println("Sent: " + resp);
                }

                socket.close();

            } catch (Exception e) {
                System.err.println("Error handling request: " + e.getMessage());
                e.printStackTrace();
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }

        private JSONObject processServiceRequest(JSONObject request) {
            JSONObject response = new JSONObject();
            response.put("type", "SERVICE_RESPONSE");

            try {
                int requestId = request.getInt("requestId");
                String serviceName = request.getString("service");
                String action = request.getString("action");
                String payload = request.getString("payload");

                Service service = ServiceFactory.get(serviceName);

                if (service == null) {
                    response.put("requestId", requestId);
                    response.put("success", false);
                    response.put("error", "Unknown service: " + serviceName);
                    return response;
                }

                String result = service.execute(action, payload);

                response.put("requestId", requestId);
                response.put("success", true);
                response.put("result", result);

            } catch (Exception e) {
                response.put("requestId", request.optInt("requestId", -1));
                response.put("success", false);
                response.put("error", e.getMessage());
            }

            return response;
        }
    }

    static class HeartbeatSender extends Thread {
        private final String routerIP;
        private final int routerPort;
        private final String serviceName;
        private final int servicePort;

        public HeartbeatSender(String routerIP, int routerPort, String serviceName, int servicePort) {
            this.routerPort = routerPort;
            this.routerIP = routerIP;
            this.serviceName = serviceName;
            this.servicePort = servicePort;
        }

        @Override
        public void run() {
            try {
                DatagramSocket socket = new DatagramSocket();
                Random random = new Random();

                while (true) {
                    JSONObject json = new JSONObject();
                    json.put("type", "HEARTBEAT");
                    json.put("service", serviceName);
                    json.put("port", servicePort);

                    byte[] buffer = json.toString().getBytes(StandardCharsets.UTF_8);

                    InetAddress address = InetAddress.getByName(routerIP);
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, routerPort);
                    socket.send(packet);

                    System.out.println("[" + System.currentTimeMillis() + "] Heartbeat sent: " + json);

                    int sleepTime = 15_000 + random.nextInt(15_001);
                    Thread.sleep(sleepTime);
                }

            } catch (Exception e) {
                System.err.println("HeartbeatSender error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println("Usage: java ServiceNode <routerIP> <routerUDPPort> <serviceName> <myTCPPort>");
            System.out.println("Example: java ServiceNode 127.0.0.1 9000 BASE64 5001");
            System.exit(1);
        }

        String routerIP = args[0];
        int routerUDPPort = Integer.parseInt(args[1]);
        String serviceName = args[2].toUpperCase().trim();
        int myServicePort = Integer.parseInt(args[3]);

        HeartbeatSender heartbeat = new HeartbeatSender(routerIP, routerUDPPort, serviceName, myServicePort);
        heartbeat.start();

        ServiceRequestHandler handler = new ServiceRequestHandler(myServicePort, serviceName);
        handler.start();

        System.out.println(serviceName + " service is running!");

        Thread.sleep(Long.MAX_VALUE);
    }
}