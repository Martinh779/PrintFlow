package com.printflow.server.socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.dispatcher.PrinterAssignment;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.sharedmodel.protocol.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TcpPrinterServer {

    private final Dispatcher dispatcher;
    private final PrintJobRepository repository;
    private final PrintJobService printJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Socket> printerConnections = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final int port;
    private volatile ServerSocket serverSocket;

    public TcpPrinterServer(
            Dispatcher dispatcher,
            PrintJobRepository repository,
            PrintJobService printJobService,
            @Value("${printflow.socket.port:50000}") int port
    ) {
        this.dispatcher = dispatcher;
        this.repository = repository;
        this.printJobService = printJobService;
        this.port = port;
    }

    @PostConstruct
    public void start() {
        executor.submit(this::runServer);
    }

    @PreDestroy
    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(port);
            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handlePrinterConnection(clientSocket));
            }
        } catch (IOException e) {
            if (serverSocket != null && !serverSocket.isClosed()) {
                throw new IllegalStateException("Printer socket server failed", e);
            }
        }
    }

    private void handlePrinterConnection(Socket socket) {
        String printerId = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                SocketMessage message = SocketMessage.fromJson(line);
                if (message == null) {
                    continue;
                }

                switch (message.getType()) {
                    case "REGISTER" -> {
                        RegisterPrinterMessage registerMessage = objectMapper.readValue(line, RegisterPrinterMessage.class);
                        printerId = registerMessage.getPrinterId();
                        dispatcher.registerPrinter(
                                new Dispatcher.PrinterRegistration(
                                        registerMessage.getPrinterId(),
                                        registerMessage.getName(),
                                        registerMessage.getHost(),
                                        registerMessage.getPort(),
                                        registerMessage.isOnline(),
                                        registerMessage.getSupportedProfiles()
                                )
                        );
                        printerConnections.put(printerId, socket);
                        dispatchPendingJobs();
                    }
                    case "STATUS_UPDATE" -> {
                        StatusUpdateMessage statusMessage = objectMapper.readValue(line, StatusUpdateMessage.class);
                        if (statusMessage.getJobId() != null) {
                            printJobService.updateStatusFromPrinter(
                                    statusMessage.getJobId(),
                                    statusMessage.getPrinterId(),
                                    statusMessage.getStatus(),
                                    statusMessage.getDetail(),
                                    statusMessage.getDurationMs(),
                                    statusMessage.isSuccessful()
                            );
                        }
                    }
                    case "ACK" -> {
                        // no-op for now
                    }
                    default -> {
                        // ignore
                    }
                }
            }
        } catch (IOException e) {
            if (printerId != null) {
                printerConnections.remove(printerId);
            }
        } finally {
            if (printerId != null) {
                printerConnections.remove(printerId);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void dispatchPendingJobs() {
        for (PrinterAssignment assignment : dispatcher.dispatchAll()) {
            Socket printerSocket = printerConnections.get(assignment.printerId());
            if (printerSocket == null || printerSocket.isClosed()) {
                dispatcher.enqueueJob(assignment.job());
                continue;
            }

            try {
                PrintJobMessage jobMessage = PrintJobMessage.fromJob(assignment.job());
                sendMessage(printerSocket, jobMessage);
            } catch (IOException e) {
                dispatcher.enqueueJob(assignment.job());
            }
        }
    }

    private void sendMessage(Socket socket, Object payload) throws IOException {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String json = payload instanceof String s ? s : objectMapper.writeValueAsString(payload);
            writer.println(json);
        }
    }
}
