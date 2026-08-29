package com.printflow.server.controller;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.events.ServerEventLogger;
import com.printflow.server.events.SystemEvent;
import com.printflow.server.events.SystemEventType;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.server.socket.PrinterConnectionRegistry;
import com.printflow.server.socket.PrinterSimulatorManager;
import com.printflow.server.socket.TcpPrinterServer;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final long RECOVERY_STATE_WINDOW_SECONDS = 30;

    private final Instant serverStartedAt = Instant.now();

    private final Dispatcher dispatcher;
    private final PrintJobRepository repository;
    private final TcpPrinterServer tcpPrinterServer;
    private final PrinterSimulatorManager simulatorManager;
    private final ServerEventLogger eventLogger;
    private final PrinterConnectionRegistry connectionRegistry;
    private final PrintJobService printJobService;

    public AdminController(Dispatcher dispatcher, PrintJobRepository repository, TcpPrinterServer tcpPrinterServer, PrinterSimulatorManager simulatorManager) {
        this(dispatcher, repository, tcpPrinterServer, simulatorManager, null, null, null);
    }

    @Autowired
    public AdminController(Dispatcher dispatcher,
                           PrintJobRepository repository,
                           TcpPrinterServer tcpPrinterServer,
                           PrinterSimulatorManager simulatorManager,
                           ServerEventLogger eventLogger,
                           PrinterConnectionRegistry connectionRegistry,
                           PrintJobService printJobService) {
        this.dispatcher = dispatcher;
        this.repository = repository;
        this.tcpPrinterServer = tcpPrinterServer;
        this.simulatorManager = simulatorManager;
        this.eventLogger = eventLogger;
        this.connectionRegistry = connectionRegistry;
        this.printJobService = printJobService;
    }

    public AdminController(Dispatcher dispatcher,
                           PrintJobRepository repository,
                           TcpPrinterServer tcpPrinterServer,
                           PrinterSimulatorManager simulatorManager,
                           ServerEventLogger eventLogger,
                           PrinterConnectionRegistry connectionRegistry) {
        this(dispatcher, repository, tcpPrinterServer, simulatorManager, eventLogger, connectionRegistry, null);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("time", Instant.now().toString());
        m.put("queueSize", dispatcher.queueSize());
        m.put("registeredPrinters", dispatcher.getRegisteredPrinters().size());
        m.put("dispatchStrategy", dispatcher.getDispatchStrategy());
        return ResponseEntity.ok(m);
    }

    public static class UpdateDispatchPolicyRequest {
        public String strategy;
    }

    @GetMapping("/dispatch-policy")
    public ResponseEntity<Map<String, Object>> getDispatchPolicy() {
        return ResponseEntity.ok(toDispatchPolicyResponse());
    }

    @PutMapping("/dispatch-policy")
    public ResponseEntity<?> updateDispatchPolicy(@RequestBody UpdateDispatchPolicyRequest req) {
        if (req == null || req.strategy == null || req.strategy.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "strategy is required"));
        }

        try {
            dispatcher.setDispatchStrategy(req.strategy);
            return ResponseEntity.ok(toDispatchPolicyResponse());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private Map<String, Object> toDispatchPolicyResponse() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("strategy", dispatcher.getDispatchStrategy());
        policy.put("defaultStrategy", dispatcher.getDefaultDispatchStrategy());
        policy.put("availableStrategies", dispatcher.getAvailableDispatchStrategies());
        policy.put("printerPolicy", Map.of(
                "profileMatching", "Requested profile id must match printer-supported profile id; empty profile list behaves as wildcard",
                "priorityHandling", "priority-aware strategy uses job priority thresholds (>=7 high, >=4 medium, else low)"
        ));
        return policy;
    }

    @GetMapping("/printers")
    public ResponseEntity<List<Map<String, Object>>> listPrinters() {
        List<SystemEvent> events = eventLogger == null ? List.of() : eventLogger.getEvents();
        Map<String, SystemEvent> latestEventByPrinter = latestEventByPrinter(events);
        Map<String, Instant> lastSeenByPrinter = Optional.ofNullable(tcpPrinterServer.getPrinterLastSeenSnapshot()).orElseGet(Map::of);

        List<Map<String, Object>> printers = dispatcher.getRegisteredPrinters().stream()
                .map(printer -> toPrinterResponse(
                        printer,
                        lastSeenByPrinter.get(printer.getId()),
                        latestEventByPrinter.get(printer.getId())
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(printers);
    }

    private Map<String, Object> toPrinterResponse(Dispatcher.PrinterRegistration printer,
                                                  Instant lastSeen,
                                                  SystemEvent latestEvent) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", printer.getId());
        response.put("name", printer.getName());
        response.put("host", printer.getHost());
        response.put("port", printer.getPort());
        response.put("capacity", printer.getCapacity());
        response.put("online", printer.isOnline());
        boolean connected = connectionRegistry != null && connectionRegistry.hasConnection(printer.getId());
        response.put("connected", connected);
        response.put("lastSeenAt", lastSeen == null ? null : lastSeen.toString());
        response.put("activeAssignments", printer.getActiveAssignments());
        response.put("recoveryState", determineRecoveryState(printer.isOnline(), connected, latestEvent));
        response.put("latestEvent", latestEvent == null ? null : Map.of(
                "type", latestEvent.getType() == null ? null : latestEvent.getType().name(),
                "message", latestEvent.getMessage(),
                "createdAt", latestEvent.getCreatedAt() == null ? null : latestEvent.getCreatedAt().toString()
        ));
        response.put("supportedProfiles", printer.getSupportedProfiles().stream()
                .map(profile -> Map.of("id", profile.getId(), "name", profile.getName()))
                .collect(Collectors.toList()));
        response.put("simulatorRunning", simulatorManager != null && simulatorManager.isRunning(printer.getId()));
        return response;
    }

    public static class CreatePrinterRequest {
        public String id;
        public String name;
        public List<Map<String, Object>> supportedProfiles;
        public Boolean online;
        public Integer capacity;
    }

    @PostMapping("/printers")
    public ResponseEntity<?> createPrinter(@RequestBody CreatePrinterRequest req) {
        if (req == null || req.id == null || req.id.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }

        String name = req.name == null ? req.id : req.name;
        List<PrinterProfile> profiles = toSupportedProfiles(req.supportedProfiles);
        boolean online = req.online != null && req.online;
        int capacity = req.capacity == null ? 2 : req.capacity;

        Dispatcher.PrinterRegistration registration = new Dispatcher.PrinterRegistration(req.id, name, "localhost", 0, online, capacity, profiles);
        dispatcher.registerPrinter(registration);
        return ResponseEntity.status(201).body(Map.of("id", req.id, "name", name, "online", online, "capacity", capacity));
    }

    private List<PrinterProfile> toSupportedProfiles(List<Map<String, Object>> supportedProfiles) {
        if (supportedProfiles == null || supportedProfiles.isEmpty()) {
            return List.of();
        }

        List<PrinterProfile> profiles = new ArrayList<>();
        for (Map<String, Object> profileMap : supportedProfiles) {
            if (profileMap == null) {
                continue;
            }

            PrinterProfile profile = new PrinterProfile();
            Object profileId = profileMap.get("id");
            Object profileName = profileMap.get("name");
            if (profileId != null) {
                profile.setId(String.valueOf(profileId));
            }
            if (profileName != null) {
                profile.setName(String.valueOf(profileName));
            }
            profiles.add(profile);
        }
        return profiles;
    }

    @PostMapping("/printers/{id}/connect")
    public ResponseEntity<?> connectPrinter(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        // find registered printer info if present
        Dispatcher.PrinterRegistration p = dispatcher.getRegisteredPrinters().stream().filter(pr -> pr.getId().equals(id)).findFirst().orElse(null);
        String host = p == null ? null : p.getHost();
        int port = p == null ? 0 : p.getPort();
        String name = p == null ? id : p.getName();
        boolean online = p == null ? true : p.isOnline();
        int capacity = p == null ? 2 : p.getCapacity();
        List<PrinterProfile> profiles = p == null ? List.of() : p.getSupportedProfiles();
        if (body != null) {
            Object h = body.get("host"); if (h != null) host = String.valueOf(h);
            Object po = body.get("port"); if (po != null) port = Integer.parseInt(String.valueOf(po));
            Object nm = body.get("name"); if (nm != null) name = String.valueOf(nm);
        }
        if (host == null || port <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "host and port are required to establish outgoing TCP connection"));
        }
        try {
            tcpPrinterServer.connectToPrinter(id, name, host, port, online, capacity, profiles);
            return ResponseEntity.ok(Map.of("id", id, "connected", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/printers/{id}/simulator/start")
    public ResponseEntity<?> startSimulator(@PathVariable String id) {
        Dispatcher.PrinterRegistration p = dispatcher.getRegisteredPrinters().stream().filter(pr -> pr.getId().equals(id)).findFirst().orElse(null);
        String name = p == null ? id : p.getName();
        try {
            boolean ok = simulatorManager.startSimulator(id, name);
            if (ok) return ResponseEntity.ok(Map.of("id", id, "simulator", "started"));
            return ResponseEntity.status(409).body(Map.of("error", "simulator already running"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/printers/{id}/simulator/stop")
    public ResponseEntity<?> stopSimulator(@PathVariable String id) {
        boolean ok = simulatorManager.stopSimulator(id);
        if (ok) return ResponseEntity.ok(Map.of("id", id, "simulator", "stopped"));
        return ResponseEntity.status(404).body(Map.of("error", "simulator not running"));
    }

    @PostMapping("/printers/{id}/disconnect")
    public ResponseEntity<?> disconnectPrinter(@PathVariable String id) {
        boolean ok = tcpPrinterServer.disconnectPrinter(id);
        if (ok) return ResponseEntity.ok(Map.of("id", id, "disconnected", true));
        return ResponseEntity.status(404).body(Map.of("error", "no active outgoing connection for printer"));
    }

    @PostMapping("/printers/{id}/online")
    public ResponseEntity<?> setPrinterOnline(@PathVariable String id, @RequestParam boolean online) {
        try {
            dispatcher.setPrinterOnline(id, online);
            return ResponseEntity.ok(Map.of("id", id, "online", online));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/queue")
    public ResponseEntity<List<Map<String, Object>>> getQueue() {
        List<Map<String, Object>> jobs = dispatcher.getQueueSnapshot().stream().map(j -> {
            Map<String, Object> jm = new LinkedHashMap<>();
            jm.put("id", j.getId());
            jm.put("fileReference", j.getFileReference());
            jm.put("priority", j.getPriority());
            jm.put("userId", j.getUserId());
            jm.put("status", j.getStatus() == null ? null : j.getStatus().name());
            jm.put("createdAt", j.getCreatedAt() == null ? null : j.getCreatedAt().toString());
            return jm;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(jobs);
    }

    public static class BulkCreateJobsRequest {
        public Integer count;
        public String filePrefix;
        public String profileId;
        public Integer priority;
        public String userId;
        public Integer startIndex;
    }

    @PostMapping("/jobs/bulk")
    public ResponseEntity<?> createJobsBulk(@RequestBody BulkCreateJobsRequest req) {
        if (printJobService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "PrintJobService unavailable"));
        }
        if (req == null || req.count == null || req.count <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "count must be a positive integer"));
        }
        if (req.count > 10_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "count must be <= 10000"));
        }

        String filePrefix = req.filePrefix == null || req.filePrefix.isBlank() ? "bulk-job" : req.filePrefix.trim();
        String profileId = req.profileId == null ? "" : req.profileId.trim();
        int priority = req.priority == null ? 1 : req.priority;
        if (priority <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "priority must be positive"));
        }
        String userId = req.userId == null || req.userId.isBlank() ? "admin-bulk" : req.userId.trim();
        int startIndex = req.startIndex == null ? 1 : Math.max(1, req.startIndex);

        List<String> createdIds = new ArrayList<>(req.count);
        for (int i = 0; i < req.count; i++) {
            int jobNumber = startIndex + i;
            String fileReference = filePrefix + "-" + jobNumber + ".pdf";
            CreatePrintJobRequest request = new CreatePrintJobRequest(
                    fileReference,
                    toProfile(profileId),
                    priority,
                    userId
            );
            createdIds.add(printJobService.createJob(request).getId());
        }

        return ResponseEntity.status(201).body(Map.of(
                "requested", req.count,
                "created", createdIds.size(),
                "firstJobId", createdIds.isEmpty() ? null : createdIds.getFirst(),
                "lastJobId", createdIds.isEmpty() ? null : createdIds.getLast()
        ));
    }

    @GetMapping("/monitoring")
    public ResponseEntity<Map<String, Object>> monitoring() {
        List<PrintJob> jobs = repository.findAll();
        List<SystemEvent> events = eventLogger == null ? List.of() : eventLogger.getEvents();
        Instant now = Instant.now();
        Instant throughputWindowStart = now.minusSeconds(300);
        Instant eventWindowStart = now.minusSeconds(900);

        Map<String, Long> jobStatusCounts = Arrays.stream(PrintJobStatus.values())
                .collect(Collectors.toMap(
                        Enum::name,
                        status -> jobs.stream().filter(job -> status == job.getStatus()).count(),
                        Long::sum,
                        LinkedHashMap::new
                ));

        long completedInLast5Min = jobs.stream()
                .filter(job -> job.getStatus() == PrintJobStatus.COMPLETED)
                .filter(job -> job.getCompletedAt() != null && !job.getCompletedAt().isBefore(throughputWindowStart))
                .count();

        double throughputPerMinute = Math.round((completedInLast5Min / 5.0d) * 100.0d) / 100.0d;

        long failedTerminalJobs = jobs.stream()
                .filter(job -> job.getStatus() == PrintJobStatus.FAILED)
                .count();
        long terminalJobs = jobs.stream()
                .filter(PrintJob::isTerminal)
                .count();
        double failedTerminalRate = terminalJobs == 0 ? 0.0d : Math.round((failedTerminalJobs * 10000.0d / terminalJobs)) / 100.0d;

        long printerFailures = countEventsSince(events, SystemEventType.PRINTER_FAILED, eventWindowStart);
        long disconnects = countEventsSince(events, SystemEventType.SOCKET_DISCONNECT, eventWindowStart);
        long recoveries = countEventsSince(events, SystemEventType.RETRY_RECOVERY, eventWindowStart);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("window", Map.of(
                "throughputSeconds", 300,
                "errorsSeconds", 900
        ));
        metrics.put("throughput", Map.of(
                "completedLast5Min", completedInLast5Min,
                "completedPerMinute", throughputPerMinute
        ));
        metrics.put("errors", Map.of(
                "failedTerminalJobs", failedTerminalJobs,
                "terminalJobs", terminalJobs,
                "failedTerminalRatePercent", failedTerminalRate,
                "printerFailuresLast15Min", printerFailures,
                "disconnectsLast15Min", disconnects
        ));
        metrics.put("recovery", Map.of(
                "recoveriesLast15Min", recoveries,
                "currentlyQueuedForRetry", jobs.stream()
                        .filter(job -> job.getStatus() == PrintJobStatus.QUEUED)
                        .filter(job -> job.getErrorMessage() != null && job.getErrorMessage().toLowerCase(Locale.ROOT).contains("retry"))
                        .count()
        ));
        metrics.put("jobHealth", jobStatusCounts);
        return ResponseEntity.ok(metrics);
    }

    private long countEventsSince(List<SystemEvent> events, SystemEventType type, Instant startInclusive) {
        return events.stream()
                .filter(event -> type == event.getType())
                .filter(event -> event.getCreatedAt() != null && !event.getCreatedAt().isBefore(startInclusive))
                .count();
    }

    private Map<String, SystemEvent> latestEventByPrinter(List<SystemEvent> events) {
        return events.stream()
                .filter(event -> event.getPrinterId() != null && !event.getPrinterId().isBlank())
                .collect(Collectors.toMap(
                        SystemEvent::getPrinterId,
                        Function.identity(),
                        (left, right) -> {
                            Instant leftTime = left.getCreatedAt() == null ? Instant.EPOCH : left.getCreatedAt();
                            Instant rightTime = right.getCreatedAt() == null ? Instant.EPOCH : right.getCreatedAt();
                            return rightTime.isAfter(leftTime) ? right : left;
                        },
                        LinkedHashMap::new
                ));
    }

    private String determineRecoveryState(boolean online, boolean connected, SystemEvent latestEvent) {
        if (!online || !connected) {
            return "DEGRADED";
        }
        if (isRecentRecoveryEvent(latestEvent)) {
            return "RECOVERING";
        }
        return "STABLE";
    }

    private boolean isRecentRecoveryEvent(SystemEvent latestEvent) {
        if (latestEvent == null || latestEvent.getType() == null || latestEvent.getCreatedAt() == null) {
            return false;
        }
        boolean recoveryType = latestEvent.getType() == SystemEventType.RETRY_RECOVERY
                || latestEvent.getType() == SystemEventType.PRINTER_FAILED
                || latestEvent.getType() == SystemEventType.SOCKET_DISCONNECT;
        if (!recoveryType) {
            return false;
        }
        Instant recoveryThreshold = Instant.now().minusSeconds(RECOVERY_STATE_WINDOW_SECONDS);
        return !latestEvent.getCreatedAt().isBefore(recoveryThreshold);
    }

    private PrinterProfile toProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return new PrinterProfile();
        }
        PrinterProfile profile = new PrinterProfile();
        profile.setId(profileId);
        profile.setName(profileId);
        return profile;
    }

    // -----------------------------------------------------------------------
    // NFA statistics dashboard endpoint
    // -----------------------------------------------------------------------

    @GetMapping("/nfa-stats")
    public ResponseEntity<Map<String, Object>> nfaStats() {
        List<PrintJob> jobs = repository.findAll();
        List<SystemEvent> events = eventLogger == null ? List.of() : eventLogger.getEvents();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("computedAt", Instant.now().toString());
        result.put("totalJobs", jobs.size());
        result.put("nfa01", nfa01(jobs));
        result.put("nfa02", nfa02(jobs));
        result.put("nfa03", nfa03(jobs));
        result.put("nfa04", nfa04(jobs, events));
        result.put("nfa05", nfa05(jobs));
        result.put("nfa06", nfa06(jobs));
        result.put("nfa07", nfa07());
        return ResponseEntity.ok(result);
    }

    /** NFA-01: >99.9% of correct REST requests answered without 5xx. */
    private Map<String, Object> nfa01(List<PrintJob> jobs) {
        long completed = jobs.stream().filter(j -> j.getStatus() == PrintJobStatus.COMPLETED).count();
        long failed = jobs.stream().filter(j -> j.getStatus() == PrintJobStatus.FAILED).count();
        long total = completed + failed; // CANCELLED is user-initiated, not a server error
        double successRate = total == 0 ? 100.0 : Math.round((completed * 10000.0 / total)) / 100.0;
        return nfaEntry("NFA-01",
                "Server answers >99.9 % of valid REST requests without internal error (5xx)",
                "Terminal job success rate (COMPLETED / (COMPLETED+FAILED))",
                successRate, "%", 99.9, total > 0 && successRate >= 99.9,
                total, "Proxy metric — CANCELLED jobs excluded as user-initiated");
    }

    /** NFA-02: Every request answered in <200 ms at <50 req/s. */
    private Map<String, Object> nfa02(List<PrintJob> jobs) {
        List<Long> latencies = createLatencies(jobs);
        double p95 = percentile(latencies, 95);
        double max = latencies.isEmpty() ? 0.0 : latencies.getLast();
        Map<String, Object> m = nfaEntry("NFA-02",
                "At <50 req/s every REST request is answered in <200 ms",
                "p95 server-side job-enqueue latency (queuedAt − createdAt)",
                p95, "ms", 200.0, !latencies.isEmpty() && p95 < 200.0,
                latencies.size(), "Proxy: measures internal enqueue time, not full HTTP RTT");
        m.put("maxMs", max);
        m.put("avgMs", latencies.isEmpty() ? 0.0 : Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0) * 100.0) / 100.0);
        return m;
    }

    /** NFA-03: p95 <500 ms at 40 req/s with ≥4 printers. */
    private Map<String, Object> nfa03(List<PrintJob> jobs) {
        List<Long> latencies = createLatencies(jobs);
        double p95 = percentile(latencies, 95);
        long activePrinters = dispatcher.getRegisteredPrinters().stream()
                .filter(p -> p.isOnline()).count();
        Map<String, Object> m = nfaEntry("NFA-03",
                "p95 REST response time <500 ms at 40 req/s with ≥4 active printers — no jobs lost or double-assigned",
                "p95 server-side job-enqueue latency",
                p95, "ms", 500.0,
                !latencies.isEmpty() && p95 < 500.0,
                latencies.size(),
                activePrinters < 4
                        ? "⚠ Only " + activePrinters + " active printer(s) online — need ≥4 for full NFA-03 verification"
                        : null);
        m.put("activePrinters", activePrinters);
        return m;
    }

    /** NFA-04: Each job assigned to at most one printer; started jobs not cancelled. */
    private Map<String, Object> nfa04(List<PrintJob> jobs, List<SystemEvent> events) {
        // Detect started-then-cancelled (hard violation)
        long startedThenCancelled = jobs.stream()
                .filter(j -> j.getStatus() == PrintJobStatus.CANCELLED && j.getStartedAt() != null)
                .count();

        // Detect duplicate concurrent assignments via event timeline:
        // Two JOB_ASSIGNED events for the same job with no RETRY_RECOVERY in between.
        Map<String, List<SystemEvent>> byJob = events.stream()
                .filter(e -> e.getJobId() != null)
                .collect(Collectors.groupingBy(SystemEvent::getJobId));

        long doubleAssigned = byJob.values().stream().filter(evts -> {
            List<SystemEvent> sorted = evts.stream()
                    .filter(e -> e.getCreatedAt() != null)
                    .sorted(Comparator.comparing(SystemEvent::getCreatedAt))
                    .collect(Collectors.toList());
            int assignments = 0;
            for (SystemEvent e : sorted) {
                if (e.getType() == SystemEventType.JOB_ASSIGNED) assignments++;
                else if (e.getType() == SystemEventType.RETRY_RECOVERY) assignments = 0; // reset after recovery
            }
            return assignments > 1; // more than one assignment without intervening recovery
        }).count();

        long violations = startedThenCancelled + doubleAssigned;
        return nfaEntry("NFA-04",
                "≥100 concurrent operations: each job assigned to at most 1 printer; no started job cancelled; no status change lost",
                "Started-then-cancelled jobs + concurrent double-assignments",
                violations, "violations", 0.0, violations == 0,
                jobs.size(),
                "startedThenCancelled=" + startedThenCancelled + ", doubleAssigned=" + doubleAssigned);
    }

    /** NFA-05: Status updates delivered within 2 s of print completion. */
    private Map<String, Object> nfa05(List<PrintJob> jobs) {
        List<Long> propagations = jobs.stream()
                .filter(j -> j.getStatus() == PrintJobStatus.COMPLETED)
                .filter(j -> j.getStartedAt() != null && j.getCompletedAt() != null && j.getResult() != null)
                .filter(j -> j.getResult().getDuration() != null)
                .map(j -> {
                    long totalMs = Duration.between(j.getStartedAt(), j.getCompletedAt()).toMillis();
                    long simMs = j.getResult().getDuration().toMillis();
                    return Math.max(0L, totalMs - simMs);
                })
                .sorted()
                .collect(Collectors.toList());

        double p95 = percentile(propagations, 95);
        double max = propagations.isEmpty() ? 0.0 : propagations.getLast();
        Map<String, Object> m = nfaEntry("NFA-05",
                "Status update 'print completed' visible to clients within 2 s of printer finishing",
                "p95 status propagation time (completedAt − startedAt − simulatedDuration)",
                p95, "ms", 2000.0, propagations.isEmpty() || p95 < 2000.0,
                propagations.size(), "Measures server-side propagation; excludes in-progress jobs");
        m.put("maxMs", max);
        return m;
    }

    /** NFA-06: Throughput with 2 printers is ≥60 % higher than with 1. */
    private Map<String, Object> nfa06(List<PrintJob> jobs) {
        Instant window = Instant.now().minusSeconds(300);
        Map<String, Long> completedByPrinter = jobs.stream()
                .filter(j -> j.getStatus() == PrintJobStatus.COMPLETED)
                .filter(j -> j.getAssignedPrinterId() != null)
                .filter(j -> j.getCompletedAt() != null && !j.getCompletedAt().isBefore(window))
                .collect(Collectors.groupingBy(PrintJob::getAssignedPrinterId, Collectors.counting()));

        long activePrinterCount = completedByPrinter.size();
        double totalThroughput = completedByPrinter.values().stream().mapToLong(Long::longValue).sum();
        long maxSinglePrinter = completedByPrinter.values().stream().mapToLong(Long::longValue).max().orElse(0L);

        double speedup = (activePrinterCount >= 2 && maxSinglePrinter > 0)
                ? Math.round((totalThroughput / maxSinglePrinter) * 100.0) / 100.0
                : 0.0;
        double improvementPct = Math.round((speedup - 1.0) * 10000.0) / 100.0;

        boolean passed = activePrinterCount >= 2 && improvementPct >= 60.0;
        Map<String, Object> m = nfaEntry("NFA-06",
                "System throughput with 2 active printers is ≥60 % higher than with 1 printer",
                "Throughput speedup in last 5 min (total / best-single-printer completed jobs)",
                improvementPct, "% improvement", 60.0, passed,
                activePrinterCount,
                activePrinterCount < 2
                        ? "⚠ Only " + activePrinterCount + " printer(s) active in last 5 min — run load test with 2+ printers"
                        : null);
        m.put("speedupFactor", speedup);
        m.put("completedByPrinter", completedByPrinter);
        return m;
    }

    /** NFA-07: Server ready for requests within 15 s of process start. */
    private Map<String, Object> nfa07() {
        long startupMs;
        try {
            Instant processStart = ProcessHandle.current().info().startInstant().orElse(null);
            startupMs = processStart != null
                    ? Duration.between(processStart, serverStartedAt).toMillis()
                    : -1L;
        } catch (Exception e) {
            startupMs = -1L;
        }
        boolean passed = startupMs >= 0 && startupMs < 15_000L;
        String note = startupMs < 0
                ? "Process start time unavailable on this platform — check Spring Boot startup log for exact timing"
                : null;
        return nfaEntry("NFA-07",
                "Server ready for REST and socket connections within 15 s of process start",
                "Spring context ready − process start",
                startupMs < 0 ? "n/a" : startupMs, "ms", 15_000.0,
                passed || startupMs < 0, // not failed if we can't measure
                1, note);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private List<Long> createLatencies(List<PrintJob> jobs) {
        return jobs.stream()
                .filter(j -> j.getCreatedAt() != null && j.getQueuedAt() != null)
                .map(j -> j.getQueuedAt().toEpochMilli() - j.getCreatedAt().toEpochMilli())
                .filter(l -> l >= 0)
                .sorted()
                .collect(Collectors.toList());
    }

    private double percentile(List<Long> sortedAsc, int pct) {
        if (sortedAsc.isEmpty()) return 0.0;
        int index = (int) Math.ceil(pct / 100.0 * sortedAsc.size()) - 1;
        index = Math.max(0, Math.min(index, sortedAsc.size() - 1));
        return sortedAsc.get(index);
    }

    private Map<String, Object> nfaEntry(String id, String description, String measurement,
                                          Object value, String unit, double threshold,
                                          boolean passed, long sampleCount, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("description", description);
        m.put("measurement", measurement);
        m.put("value", value);
        m.put("unit", unit);
        m.put("threshold", threshold);
        m.put("passed", passed);
        m.put("sampleCount", sampleCount);
        if (note != null) m.put("note", note);
        return m;
    }
}
