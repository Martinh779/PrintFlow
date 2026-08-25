package com.printflow.printerprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printflow.sharedmodel.protocol.PrintJobMessage;
import com.printflow.sharedmodel.protocol.RegisterPrinterMessage;
import com.printflow.sharedmodel.protocol.SocketMessage;
import com.printflow.sharedmodel.protocol.StatusUpdateMessage;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class TcpPrinterClient {

    private final String serverHost;
    private final int serverPort;
    private final String printerId;
    private final String printerName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TcpPrinterClient(String serverHost, int serverPort, String printerId, String printerName) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.printerId = printerId;
        this.printerName = printerName;
    }

    public void connect() {
        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            RegisterPrinterMessage register = new RegisterPrinterMessage(printerId, printerName, serverHost, serverPort, true);
            writer.println(register.toJson());

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                SocketMessage message = SocketMessage.fromJson(line);
                if ("PRINT_JOB".equalsIgnoreCase(message.getType())) {
                    PrintJobMessage printJobMessage = objectMapper.readValue(line, PrintJobMessage.class);
                    simulatePrint(printJobMessage, writer);
                }
            }
        } catch (IOException e) {
            System.out.println("Printer client could not connect to " + serverHost + ":" + serverPort + " - " + e.getMessage());
        }
    }

    private void simulatePrint(PrintJobMessage job, PrintWriter writer) throws IOException {
        long durationMs = 300 + new Random().nextInt(700);
        StatusUpdateMessage printing = StatusUpdateMessage.printing(printerId, job.getJobId());
        writer.println(printing.toJson());

        try {
            TimeUnit.MILLISECONDS.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        StatusUpdateMessage completed = StatusUpdateMessage.completed(printerId, job.getJobId(), durationMs);
        writer.println(completed.toJson());
    }
}