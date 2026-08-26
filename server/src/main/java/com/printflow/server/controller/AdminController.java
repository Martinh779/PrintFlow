package com.printflow.server.controller;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.socket.PrinterSimulatorManager;
import com.printflow.server.socket.TcpPrinterServer;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final Dispatcher dispatcher;
    private final PrintJobRepository repository;
    private final TcpPrinterServer tcpPrinterServer;
    private final PrinterSimulatorManager simulatorManager;

    public AdminController(Dispatcher dispatcher, PrintJobRepository repository, TcpPrinterServer tcpPrinterServer, PrinterSimulatorManager simulatorManager) {
        this.dispatcher = dispatcher;
        this.repository = repository;
        this.tcpPrinterServer = tcpPrinterServer;
        this.simulatorManager = simulatorManager;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("time", Instant.now().toString());
        m.put("queueSize", dispatcher.queueSize());
        m.put("registeredPrinters", dispatcher.getRegisteredPrinters().size());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/printers")
    public ResponseEntity<List<Map<String, Object>>> listPrinters() {
        List<Map<String, Object>> printers = dispatcher.getRegisteredPrinters().stream()
                .map(this::toPrinterResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(printers);
    }

    private Map<String, Object> toPrinterResponse(Dispatcher.PrinterRegistration printer) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", printer.getId());
        response.put("name", printer.getName());
        response.put("host", printer.getHost());
        response.put("port", printer.getPort());
        response.put("online", printer.isOnline());
        response.put("activeAssignments", printer.getActiveAssignments());
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
}
