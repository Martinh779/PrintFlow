package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class Dispatcher {

    private final DispatchStrategy strategy;
    private final Queue<PrintJob> queue = new ConcurrentLinkedQueue<>();
    private final Map<String, PrinterRegistration> printers = new ConcurrentHashMap<>();

    public Dispatcher() {
        this(new RoundRobinStrategy());
    }

    public Dispatcher(DispatchStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
    }

    @Data
    public static final class PrinterRegistration {
        private final String id;
        private final String name;
        private final String host;
        private final int port;
        private volatile boolean online;
        private final AtomicInteger activeAssignments = new AtomicInteger();

        public PrinterRegistration(String id, String name) {
            this(id, name, "localhost", 0, true);
        }

        public PrinterRegistration(String id, String name, boolean online) {
            this(id, name, "localhost", 0, online);
        }

        public PrinterRegistration(String id, String name, String host, int port, boolean online) {
            this.id = Objects.requireNonNull(id, "printer id must not be null");
            this.name = Objects.requireNonNull(name, "printer name must not be null");
            this.host = host == null ? "localhost" : host;
            this.port = port;
            this.online = online;
        }

        public void incrementAssignments() {
            activeAssignments.incrementAndGet();
        }

        public int getActiveAssignments() {
            return activeAssignments.get();
        }
    }

    public void registerPrinter(PrinterRegistration printer) {
        if (printer == null) {
            throw new IllegalArgumentException("printer must not be null");
        }
        printers.put(printer.getId(), printer);
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
    }

    public void unregisterPrinter(String printerId) {
        printers.remove(printerId);
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
            queue.offer(job);
            return Optional.empty();
        }

        Optional<PrinterRegistration> selected = strategy.select(candidates, job);
        if (selected.isEmpty()) {
            queue.offer(job);
            return Optional.empty();
        }

        PrinterRegistration printer = selected.get();

        try {
            if (job.getStatus() == PrintJobStatus.CANCELLED || job.isTerminal()) {
                return Optional.empty();
            }

            job.transitionTo(PrintJobStatus.ASSIGNED);
            job.setAssignedPrinterId(printer.getId());

            printer.incrementAssignments();
            return Optional.of(new PrinterAssignment(
                    job,
                    printer.getId(),
                    printer.getName(),
                    Instant.now()
            ));
        } catch (IllegalStateException e) {
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
        return assignments;
    }

    public boolean cancelQueuedJob(String jobId) {
        for (PrintJob job : queue) {
            if (job != null && jobId.equals(job.getId())) {
                try {
                    job.cancel();
                    return queue.remove(job);
                } catch (IllegalStateException ignored) {
                    return false;
                }
            }
        }
        return false;
    }
}
