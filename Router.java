package edu.qu.microcluster.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class Router {

    // serviceName -> one active service node
    private static final ConcurrentHashMap<String, ServiceInfo> activeServices = new ConcurrentHashMap<>();

    // serviceName -> queue of waiting requests
    private static final ConcurrentHashMap<String, BlockingQueue<QueuedRequest>> serviceQueues =
            new ConcurrentHashMap<>();

    static class ServiceInfo {
        String serviceName;
        String ipAddress;
        int port;
        volatile long lastHeartbeat;

        ServiceInfo(String name, String ip, int port) {
            this.serviceName = name;
            this.ipAddress = ip;
            this.port = port;
            this.lastHeartbeat = System.currentTimeMillis();
        }

        void updateHeartbeat() {
            this.lastHeartbeat = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format(
                    "%s running @ socket %s:%d (last seen: %d ms ago)",
                    serviceName, ipAddress, port,
                    System.currentTimeMillis() - lastHeartbeat
            );
        }
    }

    static class QueuedRequest {
        final JSONObject request;
        final CompletableFuture<JSONObject> future;

        QueuedRequest(JSONObject request) {
            this.request = request;
            this.future = new CompletableFuture<>();
        }
    }

    private static void ensureDispatcherRunning(String serviceName) {
        serviceQueues.computeIfAbsent(serviceName, key -> {
            BlockingQueue<QueuedRequest> queue = new ArrayBlockingQueue<>(200);

            Thread dispatcher = new Thread(() -> {
                while (true) {
                    try {
                        QueuedRequest queued = queue.take();

                        ServiceInfo serviceInfo = activeServices.get(serviceName);
                        JSONObject response;

                        if (serviceInfo == null) {
                            response = new JSONObject();
                            response.put("type", "SERVICE_RESPONSE");
                            response.put("requestId", queued.request.optInt("requestId", -1));
                            response.put("success", false);
                            response.put("error", "Service not available: " + serviceName);
                        } else {
                            response = forwardToServiceNodeStatic(
                                    serviceInfo.ipAddress,
                                    serviceInfo.port,
                                    queued.request
                            );
                        }

                        queued.future.complete(response);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

            dispatcher.setDaemon(true);
            dispatcher.setName(serviceName + "-dispatcher");
            dispatcher.start();

            System.out.println("Started dispatcher for service: " + serviceName);
            return queue;
        });
    }

    private static JSONObject forwardToServiceNodeStatic(String ip, int port, JSONObject request) {
        Socket socket = null;
        try {
            socket = new Socket(ip, port);
            socket.setSoTimeout(300_000);

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );

            out.println(request.toString());
            System.out.println("Forwarded to service node " + ip + ":" + port);

            String responseLine = in.readLine();
            if (responseLine == null) {
                throw new Exception("Service node closed connection");
            }

            System.out.println("Received from service node: " + responseLine);
            return new JSONObject(responseLine);

        } catch (SocketTimeoutException e) {
            JSONObject error = new JSONObject();
            error.put("type", "SERVICE_RESPONSE");
            error.put("requestId", request.optInt("requestId", -1));
            error.put("success", false);
            error.put("error", "Service timeout - node not responding");
            return error;

        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("type", "SERVICE_RESPONSE");
            error.put("requestId", request.optInt("requestId", -1));
            error.put("success", false);
            error.put("error", "Failed to contact service: " + e.getMessage());
            return error;

        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static class HeartbeatListener extends Thread {
        private DatagramSocket socket;
        private static final int UDP_PORT = 9000;

        public HeartbeatListener() {
            try {
                socket = new DatagramSocket(UDP_PORT);
                System.out.println("Heartbeat listener started on UDP port " + UDP_PORT);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start heartbeat listener", e);
            }
        }

        @Override
        public void run() {
            byte[] incomingData = new byte[2048];

            while (true) {
                try {
                    DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
                    socket.receive(incomingPacket);

                    String message = new String(
                            incomingPacket.getData(),
                            0,
                            incomingPacket.getLength(),
                            StandardCharsets.UTF_8
                    );

                    String senderIP = incomingPacket.getAddress().getHostAddress();

                    JSONObject json = new JSONObject(message);
                    String type = json.getString("type");

                    if ("HEARTBEAT".equals(type)) {
                        String serviceName = json.getString("service").toUpperCase().trim();
                        int servicePort = json.getInt("port");

                        if (activeServices.containsKey(serviceName)) {
                            activeServices.get(serviceName).updateHeartbeat();
                            System.out.println("Updated: " + serviceName + " from " + senderIP + ":" + servicePort);
                        } else {
                            ServiceInfo info = new ServiceInfo(serviceName, senderIP, servicePort);
                            activeServices.put(serviceName, info);
                            System.out.println("Registered: " + serviceName + " at " + senderIP + ":" + servicePort);
                        }
                    }

                    incomingData = new byte[2048];

                } catch (Exception e) {
                    System.err.println("Heartbeat listener error: " + e.getMessage());
                }
            }
        }
    }

    static class HeartbeatChecker extends Thread {
        private static final long TIMEOUT_MS = 120_000;
        private static final long CHECK_INTERVAL_MS = 30_000;

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);

                    long currentTime = System.currentTimeMillis();

                    for (String serviceName : activeServices.keySet()) {
                        ServiceInfo info = activeServices.get(serviceName);
                        if (info == null) continue;

                        long timeSinceLastHeartbeat = currentTime - info.lastHeartbeat;

                        if (timeSinceLastHeartbeat > TIMEOUT_MS) {
                            activeServices.remove(serviceName);
                            serviceQueues.remove(serviceName);
                            System.out.println("REMOVED (timeout): " + serviceName +
                                    " (no heartbeat for " + timeSinceLastHeartbeat + " ms)");
                        }
                    }

                    if (!activeServices.isEmpty()) {
                        System.out.println("\n=== Active Services ===");
                        for (ServiceInfo info : activeServices.values()) {
                            System.out.println("  " + info);
                        }
                        System.out.println("=======================\n");
                    }

                } catch (InterruptedException e) {
                    System.err.println("Heartbeat checker interrupted: " + e.getMessage());
                }
            }
        }
    }

    static class TCPServer extends Thread {
        private static final int TCP_PORT = 5050;

        @Override
        public void run() {
            try {
                ServerSocket serverSocket = new ServerSocket(TCP_PORT);
                System.out.println("TCP server listening on port " + TCP_PORT);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Client connected: " + clientSocket.getInetAddress());
                    new Thread(() -> handleClient(clientSocket)).start();
                }

            } catch (Exception e) {
                throw new RuntimeException("TCP server failed", e);
            }
        }

        private void handleClient(Socket socket) {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

                String requestLine;

                while ((requestLine = in.readLine()) != null) {
                    System.out.println("Received from client: " + requestLine);

                    if (requestLine.trim().isEmpty()) {
                        continue;
                    }

                    JSONObject request = new JSONObject(requestLine);
                    String type = request.getString("type");

                    JSONObject response;

                    if ("LIST_SERVICES".equals(type)) {
                        response = handleListServices();

                    } else if ("SERVICE_REQUEST".equals(type)) {
                        response = handleInvokeService(request);

                    } else if ("DISCONNECT".equals(type)) {
                        System.out.println("Client disconnecting");
                        break;

                    } else {
                        response = new JSONObject();
                        response.put("type", "ERROR");
                        response.put("success", false);
                        response.put("error", "Unknown request type: " + type);
                    }

                    out.println(response.toString());
                    System.out.println("Sent to client: " + response);
                }

                System.out.println("Client disconnected: " + socket.getInetAddress());
                socket.close();

            } catch (Exception e) {
                System.err.println("Error handling client: " + e.getMessage());
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }

        private JSONObject handleListServices() {
            JSONObject response = new JSONObject();
            response.put("type", "SERVICE_LIST");

            JSONArray services = new JSONArray();
            for (String serviceName : activeServices.keySet()) {
                services.put(serviceName);
            }

            response.put("services", services);
            return response;
        }

        private JSONObject handleInvokeService(JSONObject request) {
            try {
                int requestId = request.getInt("requestId");
                String serviceName = request.getString("service").toUpperCase().trim();

                ServiceInfo serviceInfo = activeServices.get(serviceName);
                if (serviceInfo == null) {
                    JSONObject error = new JSONObject();
                    error.put("type", "SERVICE_RESPONSE");
                    error.put("requestId", requestId);
                    error.put("success", false);
                    error.put("error", "Service not available: " + serviceName);
                    return error;
                }

                ensureDispatcherRunning(serviceName);

                BlockingQueue<QueuedRequest> queue = serviceQueues.get(serviceName);
                QueuedRequest queuedRequest = new QueuedRequest(request);

                boolean offered = queue.offer(queuedRequest);

                if (!offered) {
                    JSONObject error = new JSONObject();
                    error.put("type", "SERVICE_RESPONSE");
                    error.put("requestId", requestId);
                    error.put("success", false);
                    error.put("error", "Router queue full for service: " + serviceName);
                    return error;
                }

                System.out.println("Queued request #" + requestId +
                        " for service " + serviceName +
                        " | queue size = " + queue.size());

                return queuedRequest.future.get();

            } catch (Exception e) {
                JSONObject error = new JSONObject();
                error.put("type", "SERVICE_RESPONSE");
                error.put("requestId", request.optInt("requestId", -1));
                error.put("success", false);
                error.put("error", "Router error: " + e.getMessage());
                return error;
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Router Starting ===");

        HeartbeatListener listener = new HeartbeatListener();
        listener.start();

        HeartbeatChecker checker = new HeartbeatChecker();
        checker.start();

        TCPServer tcpServer = new TCPServer();
        tcpServer.start();

        System.out.println("Router is running...");
        System.out.println("Waiting for service nodes to register...\n");

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}