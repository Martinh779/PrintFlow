package com.printflow.server.socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.dispatcher.PrinterAssignment;
import com.printflow.server.events.ServerEventLogger;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.sharedmodel.protocol.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TcpPrinterServer.class);

    private final Dispatcher dispatcher;
    private final PrintJobRepository repository;
    private final PrintJobService printJobService;
    private final com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry;
    private final ServerEventLogger eventLogger;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Socket> printerConnections = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final int port;
    private volatile ServerSocket serverSocket;

    public TcpPrinterServer(
            Dispatcher dispatcher,
            PrintJobRepository repository,
            PrintJobService printJobService,
            com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry,
            ServerEventLogger eventLogger,
            @Value("${printflow.socket.port:50000}") int port
    ) {
        this.dispatcher = dispatcher;
        this.repository = repository;
        this.printJobService = printJobService;
        this.connectionRegistry = connectionRegistry;
        this.eventLogger = eventLogger == null ? new ServerEventLogger() : eventLogger;
        this.port = port;
    }

    @PostConstruct
    public void start() {
        executor.submit(this::runServer);
        log.info("Starting TCP Printer Server on port {}", port);
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
            log.info("TCP Printer Server listening on port {}", port);
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
        boolean disconnectLogged = false;
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
                        // mark this printer as having an active TCP connection so Dispatcher will consider it available
                        if (connectionRegistry != null) connectionRegistry.addConnection(printerId);
                        log.info("Printer connected and registered: {} (name={}) at {}:{} online={}",
                                registerMessage.getPrinterId(), registerMessage.getName(), registerMessage.getHost(), registerMessage.getPort(), registerMessage.isOnline());
                        dispatchPendingJobs();
                    }
                    case "STATUS_UPDATE" -> {
                        StatusUpdateMessage statusMessage = objectMapper.readValue(line, StatusUpdateMessage.class);
                        log.info("Received status update from printer {}: jobId={} status={} detail={}", statusMessage.getPrinterId(), statusMessage.getJobId(), statusMessage.getStatus(), statusMessage.getDetail());
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
            log.error("Error handling printer connection {}: {}", printerId, e.getMessage(), e);
            if (printerId != null) {
                eventLogger.recordSocketDisconnect(printerId, "Printer socket closed unexpectedly: " + e.getMessage());
                disconnectLogged = true;
                printerConnections.remove(printerId);
                if (connectionRegistry != null) connectionRegistry.removeConnection(printerId);
                printJobService.recoverJobsForPrinter(printerId);
            }
        } finally {
            if (printerId != null && !disconnectLogged) {
                eventLogger.recordSocketDisconnect(printerId, "Printer connection closed");
            }
            if (printerId != null) {
                printerConnections.remove(printerId);
                if (connectionRegistry != null) connectionRegistry.removeConnection(printerId);
                printJobService.recoverJobsForPrinter(printerId);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            log.info("Printer connection closed: {}", printerId == null ? "<unknown>" : printerId);
        }
    }

    private void dispatchPendingJobs() {
        var assignments = dispatcher.dispatchAll();
        if (assignments.isEmpty()) return;
        log.debug("dispatchPendingJobs: {} assignments to process, current printerConnections={}", assignments.size(), printerConnections.keySet());
        for (PrinterAssignment assignment : assignments) {
            try {
                log.debug("Processing assignment: printerId={} jobId={}", assignment.printerId(), assignment.job().getId());
                Socket printerSocket = printerConnections.get(assignment.printerId());
                if (printerSocket == null) {
                    log.warn("Printer socket not present for {} while job {} remains assigned. Known connections={}", assignment.printerId(), assignment.job().getId(), printerConnections.keySet());
                    continue;
                }
                if (printerSocket.isClosed()) {
                    log.warn("Printer socket is closed for {} while job {} remains assigned", assignment.printerId(), assignment.job().getId());
                    continue;
                }

                try {
                    PrintJobMessage jobMessage = PrintJobMessage.fromJob(assignment.job());
                    log.info("Dispatching job {} to printer {} via socket", assignment.job().getId(), assignment.printerId());
                    sendMessage(printerSocket, jobMessage);
                } catch (IOException e) {
                    log.warn("Failed to send job {} to printer {}, requeueing: {}", assignment.job().getId(), assignment.printerId(), e.getMessage());
                    dispatcher.unassignJob(assignment.job());
                }
            } catch (Exception e) {
                log.error("Unexpected error while dispatching job {} to {}: {}", assignment.job().getId(), assignment.printerId(), e.getMessage(), e);
                try { dispatcher.unassignJob(assignment.job()); } catch (Exception ignore) {}
            }
        }
    }

    @org.springframework.context.event.EventListener
    public void onJobEnqueued(com.printflow.server.service.JobEnqueuedEvent ev) {
        // when a job is enqueued elsewhere in the app, attempt to dispatch pending jobs to connected printers
        dispatchPendingJobs();
    }

    private void sendMessage(Socket socket, Object payload) throws IOException {
        if (socket == null || socket.isClosed()) {
            return;
        }
        String json = payload instanceof String s ? s : objectMapper.writeValueAsString(payload);
        // write directly to the socket OutputStream and do not close it
        OutputStream out = socket.getOutputStream();
        synchronized (out) {
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * Attempt to open an outgoing TCP connection to a printer host:port and register it.
     * Sends a REGISTER message and starts a read loop to process status updates.
     */
    public boolean connectToPrinter(String printerId, String name, String host, int port, boolean online, java.util.List<com.printflow.sharedmodel.model.PrinterProfile> supportedProfiles) throws IOException {
        if (printerId == null) throw new IllegalArgumentException("printerId required");
        // Prevent accidental self-connect to the server listen address which causes a loop
        try {
            if (("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) && port == this.port) {
                log.warn("Refusing to open outgoing connection to server's own listen address {}:{}", host, port);
                throw new IllegalArgumentException("Refusing to connect to server's own listen address");
            }
        } catch (Exception ignore) { /* ignore resolution errors */ }

        Socket sock = new Socket(host, port);
        sock.setKeepAlive(true);
        // send REGISTER immediately
        RegisterPrinterMessage reg = new RegisterPrinterMessage(printerId, name, host, port, online, supportedProfiles);
        sendMessage(sock, reg);
        // if there's already an active incoming connection for this printer, do not overwrite it
        if (printerConnections.containsKey(printerId)) {
            log.warn("Not storing outgoing socket for {} because an active incoming connection already exists", printerId);
            try { sock.close(); } catch (IOException ignored) {}
            return false;
        }
        // store socket and mark connection
        printerConnections.put(printerId, sock);
        if (connectionRegistry != null) connectionRegistry.addConnection(printerId);
        dispatcher.registerPrinter(new Dispatcher.PrinterRegistration(printerId, name, host, port, online, supportedProfiles));
        log.info("Opened outgoing connection and registered printer {} at {}:{}", printerId, host, port);
        dispatchPendingJobs();
        // start reader loop for incoming messages from the printer
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    SocketMessage message = SocketMessage.fromJson(line);
                    if (message == null) continue;
                    if ("STATUS_UPDATE".equals(message.getType())) {
                        StatusUpdateMessage statusMessage = SocketMessage.fromJson(line, StatusUpdateMessage.class);
                        log.info("Received status update from printer {}: jobId={} status={} detail={}", statusMessage.getPrinterId(), statusMessage.getJobId(), statusMessage.getStatus(), statusMessage.getDetail());
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
                }
            } catch (IOException e) {
                log.info("Outgoing printer connection {} closed: {}", printerId, e.getMessage());
            } finally {
                eventLogger.recordSocketDisconnect(printerId, "Outgoing printer connection closed");
                printerConnections.remove(printerId);
                if (connectionRegistry != null) connectionRegistry.removeConnection(printerId);
                printJobService.recoverJobsForPrinter(printerId);
                try { sock.close(); } catch (IOException ignored) {}
            }
        });
        return true;
    }

    public boolean disconnectPrinter(String printerId) {
        Socket s = printerConnections.remove(printerId);
        if (s != null) {
            if (connectionRegistry != null) connectionRegistry.removeConnection(printerId);
            try { s.close(); } catch (IOException ignored) {}
            log.info("Disconnected printer {} (outgoing)", printerId);
            return true;
        }
        return false;
    }
}
