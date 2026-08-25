package com.printflow.printerprocess;

import com.printflow.sharedmodel.model.PrinterProfile;
import com.printflow.sharedmodel.protocol.PrintJobMessage;
import com.printflow.sharedmodel.protocol.RegisterPrinterMessage;
import com.printflow.sharedmodel.protocol.SocketMessage;
import com.printflow.sharedmodel.protocol.StatusUpdateMessage;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TcpPrinterClient {

    private final String serverHost;
    private final int serverPort;
    private final String printerId;
    private final String printerName;
    private final int capacity;
    private final List<PrinterProfile> supportedProfiles;

    public TcpPrinterClient(String serverHost, int serverPort, String printerId, String printerName, int capacity, List<PrinterProfile> supportedProfiles) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.printerId = printerId;
        this.printerName = printerName;
        this.capacity = capacity <= 0 ? 1 : capacity;
        this.supportedProfiles = supportedProfiles == null ? List.of() : supportedProfiles;
    }

    public void connect() {
        ExecutorService pool = Executors.newFixedThreadPool(capacity);
        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // Register with supported profiles and capacity info
            RegisterPrinterMessage register = new RegisterPrinterMessage(printerId, printerName, serverHost, serverPort, true, supportedProfiles);
            writer.println(register.toJson());

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                SocketMessage message = SocketMessage.fromJson(line);
                if ("PRINT_JOB".equalsIgnoreCase(message.getType())) {
                    PrintJobMessage printJobMessage = SocketMessage.OBJECT_MAPPER.readValue(line, PrintJobMessage.class);
                    // Submit simulation to thread pool so multiple jobs can be processed concurrently
                    pool.submit(() -> {
                        try {
                            simulatePrint(printJobMessage, writer);
                        } catch (IOException e) {
                            System.err.println("Failed to send status update: " + e.getMessage());
                        }
                    });
                }
            }
        } catch (IOException e) {
            System.out.println("Printer client could not connect to " + serverHost + ":" + serverPort + " - " + e.getMessage());
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void simulatePrint(PrintJobMessage job, PrintWriter writer) throws IOException {
        long durationMs = 300 + new Random().nextInt(700);
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
