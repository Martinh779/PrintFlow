package com.printflow.server.socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printflow.sharedmodel.protocol.PrintJobMessage;
import com.printflow.sharedmodel.protocol.RegisterPrinterMessage;
import com.printflow.sharedmodel.protocol.SocketMessage;
import com.printflow.sharedmodel.protocol.StatusUpdateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Robust in-process printer simulator. Starts a background worker per printer id
 * that attempts to keep a TCP connection to the server and respond to jobs.
 * On disconnect the worker will retry after a short backoff until stopped.
 */
@Component
public class PrinterSimulatorManager {

    private static final Logger log = LoggerFactory.getLogger(PrinterSimulatorManager.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService exec = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, Future<?>> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Socket> sockets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ExecutorService> printerExecutors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    @Value("${printflow.socket.port:50000}")
    private int serverPort;
    @Value("${printflow.simulator.worker-threads:2}")
    private int simulatorWorkerThreads;
    @Value("${printflow.simulator.min-duration-ms:1500}")
    private long minDurationMs;
    @Value("${printflow.simulator.max-duration-ms:3500}")
    private long maxDurationMs;
    @Value("${printflow.simulator.heartbeat-interval-ms:3000}")
    private long heartbeatIntervalMs;
    @Value("${printflow.socket.read-timeout-ms:15000}")
    private long socketReadTimeoutMs;
    @Value("${printflow.socket.heartbeat-timeout-ms:15000}")
    private long socketHeartbeatTimeoutMs;

    public boolean startSimulator(String printerId, String name) {
        if (printerId == null) throw new IllegalArgumentException("printerId required");
        if (tasks.containsKey(printerId)) return false; // already running

        AtomicBoolean runFlag = new AtomicBoolean(true);
        running.put(printerId, runFlag);
        int workerThreads = Math.max(1, simulatorWorkerThreads);
        ExecutorService printerExecutor = Executors.newFixedThreadPool(workerThreads);
        printerExecutors.put(printerId, printerExecutor);

        Future<?> f = exec.submit(() -> {
            while (runFlag.get()) {
                Socket sock = null;
                try {
                    sock = new Socket("127.0.0.1", serverPort);
                    sock.setKeepAlive(true);
                    sockets.put(printerId, sock);

                    // send REGISTER
                    RegisterPrinterMessage reg = new RegisterPrinterMessage(printerId, name, "127.0.0.1", 0, true);
                    sendMessage(sock, reg);
                    log.info("[sim:{}] Connected and sent REGISTER", printerId);

                    long intervalMs = resolveHeartbeatIntervalMs();
                    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
                    Socket heartbeatSocketRef = sock;
                    heartbeatExecutor.scheduleAtFixedRate(
                            () -> sendHeartbeat(printerId, heartbeatSocketRef, runFlag),
                            0L,
                            intervalMs,
                            TimeUnit.MILLISECONDS
                    );

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (runFlag.get() && (line = reader.readLine()) != null) {
                            if (line.isBlank()) continue;
                            SocketMessage msg = SocketMessage.fromJson(line);
                            if (msg == null) continue;
                            if ("PRINT_JOB".equals(msg.getType())) {
                                PrintJobMessage job = SocketMessage.fromJson(line, PrintJobMessage.class);
                                log.info("[sim:{}] Received PRINT_JOB {}", printerId, job.getJobId());
                                Socket socketRef = sock;
                                printerExecutor.submit(() -> simulateJob(printerId, socketRef, job));
                            }
                        }
                    } finally {
                        heartbeatExecutor.shutdownNow();
                    }
                } catch (Exception e) {
                    log.error(String.format("[sim:%s] Connection error: %s", printerId, e.getMessage()), e);
                } finally {
                    if (sock != null) {
                        try { sock.close(); } catch (IOException ignored) {}
                        sockets.remove(printerId);
                    }
                }

                // if still supposed to run, backoff then retry
                if (runFlag.get()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    log.info("[sim:{}] Reconnecting...", printerId);
                }
            }
            log.info("[sim:{}] Simulator worker exiting", printerId);
        });

        tasks.put(printerId, f);
        log.info("Started simulator for printer {}", printerId);
        return true;
    }

    public boolean stopSimulator(String printerId) {
        AtomicBoolean flag = running.remove(printerId);
        if (flag != null) flag.set(false);
        Future<?> f = tasks.remove(printerId);
        Socket s = sockets.remove(printerId);
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        if (f != null) {
            boolean cancelled = f.cancel(true);
            shutdownPrinterExecutor(printerId);
            log.info("Stopped simulator for {} (cancelled={})", printerId, cancelled);
            return true;
        }
        shutdownPrinterExecutor(printerId);
        return false;
    }

    public boolean isRunning(String printerId) {
        return running.containsKey(printerId);
    }

    private void sendMessage(Socket socket, Object payload) throws IOException {
        if (socket == null || socket.isClosed()) return;
        String json = payload instanceof String s ? s : mapper.writeValueAsString(payload);
        OutputStream out = socket.getOutputStream();
        synchronized (out) {
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void simulateJob(String printerId, Socket socket, PrintJobMessage job) {
        if (job == null || socket == null || socket.isClosed()) {
            return;
        }

        try {
            StatusUpdateMessage printing = StatusUpdateMessage.printing(printerId, job.getJobId());
            sendMessage(socket, printing);

            long duration = nextDurationMs();
            try {
                Thread.sleep(duration);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }

            StatusUpdateMessage completed = StatusUpdateMessage.completed(printerId, job.getJobId(), duration);
            sendMessage(socket, completed);
            log.info("[sim:{}] Completed job {}", printerId, job.getJobId());
        } catch (Exception e) {
            log.warn("[sim:{}] Failed to process simulated job {}: {}", printerId, job.getJobId(), e.getMessage());
        }
    }

    private long nextDurationMs() {
        long min = Math.max(100, minDurationMs);
        long max = Math.max(min, maxDurationMs);
        if (max == min) {
            return min;
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private long resolveHeartbeatIntervalMs() {
        long configured = Math.max(heartbeatIntervalMs, 250L);
        long safeByReadTimeout = Math.max(250L, socketReadTimeoutMs / 3L);
        long safeByHeartbeatTimeout = Math.max(250L, socketHeartbeatTimeoutMs / 3L);
        return Math.min(configured, Math.min(safeByReadTimeout, safeByHeartbeatTimeout));
    }

    private void sendHeartbeat(String printerId, Socket socket, AtomicBoolean runFlag) {
        if (!runFlag.get()) {
            return;
        }
        try {
            SocketMessage heartbeat = new SocketMessage("HEARTBEAT");
            heartbeat.setPrinterId(printerId);
            sendMessage(socket, heartbeat);
        } catch (Exception e) {
            log.debug("[sim:{}] Heartbeat send failed: {}", printerId, e.getMessage());
        }
    }

    private void shutdownPrinterExecutor(String printerId) {
        ExecutorService printerExecutor = printerExecutors.remove(printerId);
        if (printerExecutor != null) {
            printerExecutor.shutdownNow();
        }
    }
}
