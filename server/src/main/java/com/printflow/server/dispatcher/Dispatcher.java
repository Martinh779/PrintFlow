package com.printflow.server.dispatcher;

import com.printflow.server.events.ServerEventLogger;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
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

    private final DispatchStrategy strategy;
    private final Queue<PrintJob> queue = new ConcurrentLinkedQueue<>();
    private final Map<String, PrinterRegistration> printers = new ConcurrentHashMap<>();
    private final com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry;
    private final ServerEventLogger eventLogger;

    public Dispatcher() {
        this(new RoundRobinStrategy(), null, new ServerEventLogger());
    }

    public Dispatcher(DispatchStrategy strategy) {
        this(strategy, null, new ServerEventLogger());
    }

    public Dispatcher(DispatchStrategy strategy, com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry) {
        this(strategy, connectionRegistry, new ServerEventLogger());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Dispatcher(DispatchStrategy strategy,
                      com.printflow.server.socket.PrinterConnectionRegistry connectionRegistry,
                      ServerEventLogger eventLogger) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.connectionRegistry = connectionRegistry;
        this.eventLogger = eventLogger == null ? new ServerEventLogger() : eventLogger;
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

            public boolean supportsProfile(com.printflow.sharedmodel.model.PrinterProfile profile) {
                if (profile == null || profile.getId() == null) return true; // treat null as wildcard
                if (supportedProfiles == null || supportedProfiles.isEmpty()) return true; // no restriction
                return supportedProfiles.stream().anyMatch(p -> profile.getId().equals(p.getId()));
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

        if (job.isTerminal() || job.getStatus() == PrintJobStatus.ASSIGNED) {
            return false;
        }

        if (job.getStatus() == PrintJobStatus.CREATED) {
            job.transitionTo(PrintJobStatus.QUEUED);
        } else if (job.getStatus() != PrintJobStatus.QUEUED) {
            throw new IllegalStateException("Only CREATED or QUEUED jobs can enter the dispatcher queue");
        }

        return queue.offer(job);
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
                .filter(p -> connectionRegistry == null
                        || connectionRegistry.getConnected().isEmpty()
                        || connectionRegistry.hasConnection(p.getId()))
                .toList();
    }

    public Optional<PrinterAssignment> dispatchNext() {
        PrintJob job = queue.poll();
        if (job == null) {
            return Optional.empty();
        }

        if (job.isTerminal() || job.getStatus() == PrintJobStatus.ASSIGNED) {
            return Optional.empty();
        }

        List<PrinterRegistration> candidates = getActivePrinters();
        if (candidates.isEmpty()) {
            log.debug("No active printers available, requeueing job {}", job.getId());
            queue.offer(job);
            return Optional.empty();
        }

        Optional<PrinterRegistration> selected = strategy.select(candidates, job);
        if (selected.isEmpty()) {
            log.debug("No suitable printer selected for job {}, requeueing", job.getId());
            queue.offer(job);
            return Optional.empty();
        }

        PrinterRegistration printer = selected.get();
        log.debug("Selected printer {} for job {}", printer.getId(), job.getId());

        try {
            if (job.getStatus() == PrintJobStatus.CANCELLED || job.isTerminal()) {
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
            log.warn("Failed to assign job {} due to illegal state, requeueing", job.getId());
            queue.offer(job);
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
        if (job == null) return;
        if (job.getStatus() != PrintJobStatus.ASSIGNED && job.getStatus() != PrintJobStatus.PRINTING) return;

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
                "Job returned to queue for retry after reassignment");
        queue.offer(job);
    }

    public boolean cancelQueuedJob(String jobId) {
        for (PrintJob job : queue) {
            if (job != null && jobId.equals(job.getId())) {
                try {
                    job.cancel();
                    boolean removed = queue.remove(job);
                    if (removed) {
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
}
