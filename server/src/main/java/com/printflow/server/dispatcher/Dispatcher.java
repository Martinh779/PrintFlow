package com.printflow.server.dispatcher;

import com.printflow.server.events.ServerEventLogger;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final EnumMap<DispatchStrategyType, DispatchStrategy> availableStrategies;
    private final DispatchStrategyType defaultStrategyType;
    private volatile DispatchStrategyType activeStrategyType;
    private final Queue<PrintJob> queue = new ConcurrentLinkedQueue<>();
    private final Set<String> queuedJobIds = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlightJobIds = ConcurrentHashMap.newKeySet();
    private final Map<String, PrinterRegistration> printers = new ConcurrentHashMap<>();
    private final com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry;
    private final ServerEventLogger eventLogger;

    public Dispatcher() {
        this(DispatchStrategyType.ROUND_ROBIN, null, new ServerEventLogger());
    }

    public Dispatcher(DispatchStrategy strategy) {
        this(strategy, null, new ServerEventLogger());
    }

    public Dispatcher(DispatchStrategy strategy, com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry) {
        this(strategy, connectionRegistry, new ServerEventLogger());
    }

    public Dispatcher(String strategyKey, com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry) {
        this(strategyKey, connectionRegistry, new ServerEventLogger());
    }

    public Dispatcher(String strategyKey,
                      com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry,
                      ServerEventLogger eventLogger) {
        this(resolveRequestedStrategyType(strategyKey), connectionRegistry, eventLogger);
    }

    private Dispatcher(DispatchStrategyType defaultStrategyType,
                       com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry,
                       ServerEventLogger eventLogger) {
        this.defaultStrategyType = Objects.requireNonNull(defaultStrategyType, "defaultStrategyType must not be null");
        this.activeStrategyType = this.defaultStrategyType;
        this.connectionRegistry = connectionRegistry;
        this.eventLogger = eventLogger == null ? new ServerEventLogger() : eventLogger;
        this.availableStrategies = createDefaultStrategyMap();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Dispatcher(DispatchStrategy strategy,
                      com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry,
                      ServerEventLogger eventLogger) {
        DispatchStrategyType strategyType = resolveStrategyType(strategy);
        this.defaultStrategyType = strategyType;
        this.activeStrategyType = strategyType;
        this.connectionRegistry = connectionRegistry;
        this.eventLogger = eventLogger == null ? new ServerEventLogger() : eventLogger;
        this.availableStrategies = createDefaultStrategyMap();
        this.availableStrategies.put(strategyType, strategy);
    }

    private static EnumMap<DispatchStrategyType, DispatchStrategy> createDefaultStrategyMap() {
        EnumMap<DispatchStrategyType, DispatchStrategy> strategyMap = new EnumMap<>(DispatchStrategyType.class);
        strategyMap.put(DispatchStrategyType.ROUND_ROBIN, new RoundRobinStrategy());
        strategyMap.put(DispatchStrategyType.LEAST_LOADED, new LeastLoadedStrategy());
        strategyMap.put(DispatchStrategyType.PRIORITY_AWARE, new PriorityAwareStrategy());
        return strategyMap;
    }

    private static DispatchStrategyType resolveStrategyType(DispatchStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (strategy instanceof LeastLoadedStrategy) {
            return DispatchStrategyType.LEAST_LOADED;
        }
        if (strategy instanceof PriorityAwareStrategy) {
            return DispatchStrategyType.PRIORITY_AWARE;
        }
        return DispatchStrategyType.ROUND_ROBIN;
    }

    private static DispatchStrategyType resolveRequestedStrategyType(String strategyKey) {
        return DispatchStrategyType.fromValue(strategyKey).orElse(DispatchStrategyType.ROUND_ROBIN);
    }

    private DispatchStrategy activeStrategy() {
        return availableStrategies.getOrDefault(activeStrategyType, availableStrategies.get(defaultStrategyType));
    }

    public synchronized void setDispatchStrategy(String strategyKey) {
        DispatchStrategyType resolved = DispatchStrategyType.fromValue(strategyKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown dispatch strategy: " + strategyKey));
        this.activeStrategyType = resolved;
        log.info("Dispatch strategy changed to {}", resolved.value());
    }

    public String getDispatchStrategy() {
        return activeStrategyType.value();
    }

    public String getDefaultDispatchStrategy() {
        return defaultStrategyType.value();
    }

    public List<Map<String, String>> getAvailableDispatchStrategies() {
        return Arrays.stream(DispatchStrategyType.values())
                .map(strategyType -> Map.of(
                        "key", strategyType.value(),
                        "label", strategyType.label()
                ))
                .toList();
    }

        public static final class PrinterRegistration {
            private final String id;
            private final String name;
            private final String host;
            private final int port;
            private volatile boolean online;
            private final AtomicInteger activeAssignments = new AtomicInteger();
            private final java.util.List<com.printflow.sharedmodel.model.PrinterProfile> supportedProfiles;

            public PrinterRegistration(String id, String name) {
                this(id, name, "localhost", 0, true, java.util.List.of());
            }

            public PrinterRegistration(String id, String name, boolean online) {
                this(id, name, "localhost", 0, online, java.util.List.of());
            }

            public PrinterRegistration(String id, String name, String host, int port, boolean online) {
                this(id, name, host, port, online, java.util.List.of());
            }

            public PrinterRegistration(String id, String name, String host, int port, boolean online, java.util.List<com.printflow.sharedmodel.model.PrinterProfile> supportedProfiles) {
                this.id = Objects.requireNonNull(id, "printer id must not be null");
                this.name = Objects.requireNonNull(name, "printer name must not be null");
                this.host = host == null ? "localhost" : host;
                this.port = port;
                this.online = online;
                this.supportedProfiles = supportedProfiles == null ? java.util.List.of() : supportedProfiles;
            }

            public String getId() { return id; }
            public String getName() { return name; }
            public String getHost() { return host; }
            public int getPort() { return port; }
            public boolean isOnline() { return online; }
            public void setOnline(boolean online) { this.online = online; }

            public void incrementAssignments() {
                activeAssignments.incrementAndGet();
            }

            public void decrementAssignments() {
                int v = activeAssignments.decrementAndGet();
                if (v < 0) {
                    // guard against underflow in case of logic errors elsewhere
                    activeAssignments.set(0);
                }
            }

            public int getActiveAssignments() {
                return activeAssignments.get();
        }

            public java.util.List<com.printflow.sharedmodel.model.PrinterProfile> getSupportedProfiles() {
                return supportedProfiles;
            }

            public int profileMatchSpecificity(PrinterProfile profile) {
                if (profile == null || profile.getId() == null || profile.getId().isBlank()) {
                    return 1;
                }
                if (supportedProfiles == null || supportedProfiles.isEmpty()) {
                    return 1;
                }
                return supportedProfiles.stream().anyMatch(p -> profile.getId().equals(p.getId())) ? 0 : Integer.MAX_VALUE;
            }

            public boolean supportsProfile(PrinterProfile profile) {
                return profileMatchSpecificity(profile) != Integer.MAX_VALUE;
            }
        }

    public void registerPrinter(PrinterRegistration printer) {
        if (printer == null) {
            throw new IllegalArgumentException("printer must not be null");
        }

        PrinterRegistration existing = printers.get(printer.getId());
        if (existing != null) {
            existing.setOnline(existing.isOnline() && printer.isOnline());
            log.info("Printer registration refreshed for {} (online={})", printer.getId(), existing.isOnline());
            return;
        }

        printers.put(printer.getId(), printer);
        log.info("Printer registered: {} (online={})", printer.getId(), printer.isOnline());
    }

    public void registerPrinter(String id, String name) {
        registerPrinter(new PrinterRegistration(id, name, true));
    }

    public void registerPrinter(String id, String name, boolean online) {
        registerPrinter(new PrinterRegistration(id, name, online));
    }

    public void setPrinterOnline(String printerId, boolean online) {
        PrinterRegistration printer = printers.get(printerId);
        if (printer == null) {
            throw new IllegalArgumentException("Unknown printer: " + printerId);
        }
        printer.setOnline(online);
        log.info("Printer {} online set to {}", printerId, online);
    }

    public void unregisterPrinter(String printerId) {
        printers.remove(printerId);
        log.info("Printer unregistered: {}", printerId);
    }

    public boolean enqueue(PrintJob job) {
        if (job == null) {
            throw new IllegalArgumentException("job must not be null");
        }
        if (job.getId() == null || job.getId().isBlank()) {
            throw new IllegalArgumentException("job id must not be null or blank");
        }

        if (job.isTerminal() || job.getStatus() == PrintJobStatus.ASSIGNED) {
            return false;
        }
        if (inFlightJobIds.contains(job.getId())) {
            return false;
        }

        if (job.getStatus() == PrintJobStatus.CREATED) {
            job.transitionTo(PrintJobStatus.QUEUED);
        } else if (job.getStatus() != PrintJobStatus.QUEUED) {
            throw new IllegalStateException("Only CREATED or QUEUED jobs can enter the dispatcher queue");
        }

        if (!queuedJobIds.add(job.getId())) {
            return false;
        }

        boolean offered = queue.offer(job);
        if (!offered) {
            queuedJobIds.remove(job.getId());
        }
        return offered;
    }

    public boolean enqueueJob(PrintJob job) {
        return enqueue(job);
    }

    public int queueSize() {
        return queue.size();
    }

    public List<PrintJob> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    public List<PrinterRegistration> getRegisteredPrinters() {
        return new ArrayList<>(printers.values());
    }

    public List<PrinterRegistration> getActivePrinters() {
        return printers.values().stream()
                .filter(PrinterRegistration::isOnline)
                .filter(p -> connectionRegistry == null || connectionRegistry.hasConnection(p.getId()))
                .toList();
    }

    public synchronized Optional<PrinterAssignment> dispatchNext() {
        PrintJob job = pollNextDispatchableJob();
        if (job == null) {
            return Optional.empty();
        }

        List<PrinterRegistration> candidates = getActivePrinters();
        if (candidates.isEmpty()) {
            log.debug("No active printers available, requeueing job {}", job.getId());
            requeueJob(job);
            return Optional.empty();
        }

        Optional<PrinterRegistration> selected = activeStrategy().select(candidates, job);
        if (selected.isEmpty()) {
            log.debug("No suitable printer selected for job {}, requeueing", job.getId());
            requeueJob(job);
            return Optional.empty();
        }

        PrinterRegistration printer = selected.get();
        log.debug("Selected printer {} for job {}", printer.getId(), job.getId());

        try {
            if (job.getStatus() == PrintJobStatus.CANCELLED || job.isTerminal()) {
                return Optional.empty();
            }
            if (!inFlightJobIds.add(job.getId())) {
                log.warn("Job {} is already in-flight, skipping duplicate assignment attempt", job.getId());
                return Optional.empty();
            }

            job.transitionTo(PrintJobStatus.ASSIGNED);
            job.setAssignedPrinterId(printer.getId());

            printer.incrementAssignments();
            eventLogger.recordJobAssigned(job.getId(), printer.getId(), printer.getName());
            log.info("Assigned job {} to printer {}", job.getId(), printer.getId());
            return Optional.of(new PrinterAssignment(
                    job,
                    printer.getId(),
                    printer.getName(),
                    Instant.now()
            ));
        } catch (IllegalStateException e) {
            inFlightJobIds.remove(job.getId());
            log.warn("Failed to assign job {} due to illegal state, requeueing", job.getId());
            requeueJob(job);
            return Optional.empty();
        }
    }

    public List<PrinterAssignment> dispatchAll() {
        List<PrinterAssignment> assignments = new ArrayList<>();
        Optional<PrinterAssignment> next;
        do {
            next = dispatchNext();
            next.ifPresent(assignments::add);
        } while (next.isPresent());
        if (!assignments.isEmpty()) {
            log.info("DispatchAll created {} assignments", assignments.size());
        }
        return assignments;
    }

    /**
     * Revert an assignment so the job can be requeued. This will clear the
     * assigned printer id, transition the job back to QUEUED and decrement the
     * printer's active assignment counter.
     */
    public void unassignJob(PrintJob job) {
        unassignJob(job, "Job returned to queue for retry after reassignment");
    }

    public synchronized void unassignJob(PrintJob job, String reason) {
        if (job == null) return;
        if (job.getStatus() != PrintJobStatus.ASSIGNED && job.getStatus() != PrintJobStatus.PRINTING) return;

        inFlightJobIds.remove(job.getId());
        String pid = job.getAssignedPrinterId();
        job.setAssignedPrinterId(null);

        try {
            job.transitionTo(PrintJobStatus.QUEUED);
        } catch (IllegalStateException ignored) {
            // keep the job queued if a retry path is needed
            job.setStatus(PrintJobStatus.QUEUED);
        }

        if (pid != null) {
            PrinterRegistration pr = printers.get(pid);
            if (pr != null) {
                pr.decrementAssignments();
            }
        }
        eventLogger.recordRetryRecovery(job.getId(), pid,
                reason == null || reason.isBlank() ? "Job returned to queue for retry after reassignment" : reason);
        requeueJob(job);
    }

    public synchronized void completeAssignment(PrintJob job) {
        if (job == null || job.getId() == null || job.getId().isBlank()) {
            return;
        }
        inFlightJobIds.remove(job.getId());
        queuedJobIds.remove(job.getId());

        String pid = job.getAssignedPrinterId();
        if (pid == null || pid.isBlank()) {
            return;
        }

        PrinterRegistration pr = printers.get(pid);
        if (pr != null) {
            pr.decrementAssignments();
        }
    }

    public synchronized boolean cancelQueuedJob(String jobId) {
        for (PrintJob job : queue) {
            if (job != null && jobId.equals(job.getId())) {
                try {
                    job.cancel();
                    boolean removed = queue.remove(job);
                    if (removed) {
                        queuedJobIds.remove(jobId);
                        inFlightJobIds.remove(jobId);
                        log.info("Cancelled and removed queued job {}", jobId);
                    }
                    return removed;
                } catch (IllegalStateException ignored) {
                    log.warn("Failed to cancel queued job {} due to illegal state", jobId);
                    return false;
                }
            }
        }
        log.debug("No queued job found with id {} to cancel", jobId);
        return false;
    }

    private PrintJob pollNextDispatchableJob() {
        while (true) {
            PrintJob job = queue.poll();
            if (job == null) {
                return null;
            }
            if (job.getId() != null) {
                queuedJobIds.remove(job.getId());
            }
            if (job.isTerminal() || job.getStatus() == PrintJobStatus.ASSIGNED) {
                continue;
            }
            return job;
        }
    }

    private void requeueJob(PrintJob job) {
        if (job == null || job.getId() == null || job.getId().isBlank()) {
            return;
        }
        if (job.isTerminal()) {
            queuedJobIds.remove(job.getId());
            return;
        }
        if (queuedJobIds.add(job.getId())) {
            queue.offer(job);
        }
    }
}
