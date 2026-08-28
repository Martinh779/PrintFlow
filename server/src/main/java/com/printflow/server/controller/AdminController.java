package com.printflow.server.controller;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.events.ServerEventLogger;
import com.printflow.server.events.SystemEvent;
import com.printflow.server.events.SystemEventType;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.socket.PrinterConnectionRegistry;
import com.printflow.server.socket.PrinterSimulatorManager;
import com.printflow.server.socket.TcpPrinterServer;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final Dispatcher dispatcher;
    private final PrintJobRepository repository;
    private final TcpPrinterServer tcpPrinterServer;
    private final PrinterSimulatorManager simulatorManager;
    private final ServerEventLogger eventLogger;
    private final PrinterConnectionRegistry connectionRegistry;

    public AdminController(Dispatcher dispatcher, PrintJobRepository repository, TcpPrinterServer tcpPrinterServer, PrinterSimulatorManager simulatorManager) {
        this(dispatcher, repository, tcpPrinterServer, simulatorManager, null, null);
    }

    @Autowired
    public AdminController(Dispatcher dispatcher,
                           PrintJobRepository repository,
                           TcpPrinterServer tcpPrinterServer,
                           PrinterSimulatorManager simulatorManager,
                           ServerEventLogger eventLogger,
                           PrinterConnectionRegistry connectionRegistry) {
        this.dispatcher = dispatcher;
        this.repository = repository;
        this.tcpPrinterServer = tcpPrinterServer;
        this.simulatorManager = simulatorManager;
        this.eventLogger = eventLogger;
        this.connectionRegistry = connectionRegistry;
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
        Set<String> recoveringPrinterIds = recoveringPrinterIds(events, Instant.now().minusSeconds(300));
        Map<String, Instant> lastSeenByPrinter = Optional.ofNullable(tcpPrinterServer.getPrinterLastSeenSnapshot()).orElseGet(Map::of);

        List<Map<String, Object>> printers = dispatcher.getRegisteredPrinters().stream()
                .map(printer -> toPrinterResponse(
                        printer,
                        lastSeenByPrinter.get(printer.getId()),
                        latestEventByPrinter.get(printer.getId()),
                        recoveringPrinterIds.contains(printer.getId())
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(printers);
    }

    private Map<String, Object> toPrinterResponse(Dispatcher.PrinterRegistration printer,
                                                  Instant lastSeen,
                                                  SystemEvent latestEvent,
                                                  boolean recovering) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", printer.getId());
        response.put("name", printer.getName());
        response.put("host", printer.getHost());
        response.put("port", printer.getPort());
        response.put("online", printer.isOnline());
        boolean connected = connectionRegistry != null && connectionRegistry.hasConnection(printer.getId());
        response.put("connected", connected);
        response.put("lastSeenAt", lastSeen == null ? null : lastSeen.toString());
        response.put("activeAssignments", printer.getActiveAssignments());
        response.put("recoveryState", determineRecoveryState(printer.isOnline(), connected, recovering));
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
    }

    @PostMapping("/printers")
    public ResponseEntity<?> createPrinter(@RequestBody CreatePrinterRequest req) {
        if (req == null || req.id == null || req.id.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }

        String name = req.name == null ? req.id : req.name;
        List<PrinterProfile> profiles = toSupportedProfiles(req.supportedProfiles);
        boolean online = req.online == null ? false : req.online;

        Dispatcher.PrinterRegistration registration = new Dispatcher.PrinterRegistration(req.id, name, "localhost", 0, online, profiles);
        dispatcher.registerPrinter(registration);
        return ResponseEntity.status(201).body(Map.of("id", req.id, "name", name, "online", online));
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
            tcpPrinterServer.connectToPrinter(id, name, host, port, online, profiles);
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

    private Set<String> recoveringPrinterIds(List<SystemEvent> events, Instant windowStart) {
        return events.stream()
                .filter(event -> event.getPrinterId() != null && !event.getPrinterId().isBlank())
                .filter(event -> event.getCreatedAt() != null && !event.getCreatedAt().isBefore(windowStart))
                .filter(event -> event.getType() == SystemEventType.RETRY_RECOVERY
                        || event.getType() == SystemEventType.PRINTER_FAILED
                        || event.getType() == SystemEventType.SOCKET_DISCONNECT)
                .map(SystemEvent::getPrinterId)
                .collect(Collectors.toSet());
    }

    private String determineRecoveryState(boolean online, boolean connected, boolean recovering) {
        if (recovering) {
            return "RECOVERING";
        }
        if (!online || !connected) {
            return "DEGRADED";
        }
        return "STABLE";
    }
}
