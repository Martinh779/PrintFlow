package com.printflow.printerprocess;

import com.printflow.sharedmodel.model.PrinterProfile;
import com.printflow.sharedmodel.protocol.PrintJobMessage;
import com.printflow.sharedmodel.protocol.RegisterPrinterMessage;
import com.printflow.sharedmodel.protocol.SocketMessage;
import com.printflow.sharedmodel.protocol.StatusUpdateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TcpPrinterClient {

    private static final Logger log = LoggerFactory.getLogger(TcpPrinterClient.class);

    private final String serverHost;
    private final int serverPort;
    private final String printerId;
    private final String printerName;
    private final int capacity;
    private final List<PrinterProfile> supportedProfiles;

    // reconnect interval in ms
    private final long reconnectIntervalMs;
    private final long heartbeatIntervalMs;

    public TcpPrinterClient(String serverHost, int serverPort, String printerId, String printerName, int capacity, List<PrinterProfile> supportedProfiles) {
        this(serverHost, serverPort, printerId, printerName, capacity, supportedProfiles, 2000, 3000);
    }

    public TcpPrinterClient(String serverHost, int serverPort, String printerId, String printerName, int capacity, List<PrinterProfile> supportedProfiles, long reconnectIntervalMs) {
        this(serverHost, serverPort, printerId, printerName, capacity, supportedProfiles, reconnectIntervalMs, 3000);
    }

    public TcpPrinterClient(String serverHost, int serverPort, String printerId, String printerName, int capacity, List<PrinterProfile> supportedProfiles, long reconnectIntervalMs, long heartbeatIntervalMs) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.printerId = printerId;
        this.printerName = printerName;
        this.capacity = capacity <= 0 ? 1 : capacity;
        this.supportedProfiles = supportedProfiles == null ? List.of() : supportedProfiles;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? 3000 : heartbeatIntervalMs;
    }

    public void connect() {
        ExecutorService pool = Executors.newFixedThreadPool(capacity);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket socket = new Socket(serverHost, serverPort);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    log.info("Connected to print server {}:{}", serverHost, serverPort);

                    // Register with supported profiles and capacity info
                    RegisterPrinterMessage register = new RegisterPrinterMessage(printerId, printerName, serverHost, serverPort, true, supportedProfiles);
                    writer.println(register.toJson());
                    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
                    heartbeatExecutor.scheduleAtFixedRate(() -> sendHeartbeat(writer), heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank()) {
                                continue;
                            }
                            SocketMessage message = SocketMessage.fromJson(line);
                            if (message == null) continue;
                            if ("PRINT_JOB".equalsIgnoreCase(message.getType())) {
                                PrintJobMessage printJobMessage = SocketMessage.OBJECT_MAPPER.readValue(line, PrintJobMessage.class);
                                // Submit simulation to thread pool so multiple jobs can be processed concurrently
                                pool.submit(() -> {
                                    try {
                                        simulatePrint(printJobMessage, writer);
                                    } catch (IOException e) {
                                        log.error("Failed to send status update: {}", e.getMessage());
                                    }
                                });
                            }
                        }
                    } finally {
                        heartbeatExecutor.shutdownNow();
                    }

                    log.info("Connection closed by server {}:{}", serverHost, serverPort);
                } catch (IOException e) {
                    log.warn("Printer client could not connect to {}:{} - {}", serverHost, serverPort, e.getMessage());
                }

                // sleep before retrying
                try {
                    log.info("Reconnecting to {}:{} in {} ms", serverHost, serverPort, reconnectIntervalMs);
                    TimeUnit.MILLISECONDS.sleep(reconnectIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendHeartbeat(PrintWriter writer) {
        try {
            SocketMessage heartbeat = new SocketMessage("HEARTBEAT");
            heartbeat.setPrinterId(printerId);
            synchronized (writer) {
                writer.println(heartbeat.toJson());
            }
        } catch (Exception e) {
            log.debug("Failed to send heartbeat for printer {}: {}", printerId, e.getMessage());
        }
    }

    private void simulatePrint(PrintJobMessage job, PrintWriter writer) throws IOException {
        long durationMs = 3000 + new Random().nextInt(7000);
        StatusUpdateMessage printing = StatusUpdateMessage.printing(printerId, job.getJobId());
        synchronized (writer) {
            writer.println(printing.toJson());
        }

        try {
            TimeUnit.MILLISECONDS.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        StatusUpdateMessage completed = StatusUpdateMessage.completed(printerId, job.getJobId(), durationMs);
        synchronized (writer) {
            writer.println(completed.toJson());
        }
    }
}
