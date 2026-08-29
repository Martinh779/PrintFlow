package com.printflow.server.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.printflow.sharedmodel.model.PrintJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ServerEventLogger {
    private static final Logger log = LoggerFactory.getLogger(ServerEventLogger.class);
    private static final TypeReference<List<SystemEvent>> EVENT_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path storagePath;
    private final List<SystemEvent> events = new CopyOnWriteArrayList<>();

    public ServerEventLogger() {
        this(createDefaultObjectMapper(), Path.of("data", "printflow-events.json"));
    }

    public ServerEventLogger(Path storagePath) {
        this(createDefaultObjectMapper(), storagePath);
    }

    public ServerEventLogger(ObjectMapper objectMapper, Path storagePath) {
        this.objectMapper = configureObjectMapper(objectMapper);
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath must not be null");
        load();
    }

    public ServerEventLogger(@Value("${printflow.storage.events.file:data/printflow-events.json}") String storagePath) {
        this(createDefaultObjectMapper(), Path.of(storagePath));
    }

    private static ObjectMapper createDefaultObjectMapper() {
        return configureObjectMapper(new ObjectMapper());
    }

    private static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    public void record(SystemEventType type, String jobId, String printerId, String message, Map<String, Object> details) {
        if (type == null) {
            throw new IllegalArgumentException("event type must not be null");
        }

        SystemEvent event = new SystemEvent(
                type,
                type.getLabel(),
                jobId,
                printerId,
                message == null || message.isBlank() ? type.getLabel() : message,
                details == null ? Map.of() : new LinkedHashMap<>(details),
                Instant.now()
        );
        events.add(event);
        persist();
        log.info("{}: jobId={} printerId={} message={}", type.name(), jobId, printerId, event.getMessage());
    }

    public void recordJobCreated(PrintJob job) {
        if (job == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fileReference", job.getFileReference());
        details.put("profileId", job.getProfile() == null ? null : job.getProfile().getId());
        details.put("priority", job.getPriority());
        details.put("userId", job.getUserId());
        record(SystemEventType.JOB_CREATED, job.getId(), null, "Job created", details);
    }

    public void recordJobQueued(PrintJob job) {
        if (job == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", job.getStatus());
        details.put("queuedAt", job.getQueuedAt() == null ? Instant.now() : job.getQueuedAt());
        record(SystemEventType.JOB_QUEUED, job.getId(), null, "Job queued for processing", details);
    }

    public void recordJobAssigned(String jobId, String printerId, String printerName) {
        record(SystemEventType.JOB_ASSIGNED, jobId, printerId, "Job assigned to printer",
                Map.of("printerName", printerName == null ? "unknown" : printerName));
    }

    public void recordJobCompleted(PrintJob job, String printerId, String detail) {
        if (job == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detail", detail == null ? "Completed successfully" : detail);
        details.put("status", job.getStatus());
        record(SystemEventType.JOB_COMPLETED, job.getId(), printerId, "Job completed", details);
    }

    public void recordJobCancelled(String jobId, String reason) {
        record(SystemEventType.JOB_CANCELLED, jobId, null, "Job cancelled",
                Map.of("reason", reason == null ? "Cancelled by user" : reason));
    }

    public void recordPrinterFailure(String jobId, String printerId, String detail, long durationMs) {
        record(SystemEventType.PRINTER_FAILED, jobId, printerId, "Printer reported failure",
                Map.of("detail", detail == null ? "Unknown printer failure" : detail,
                        "durationMs", durationMs));
    }

    public void recordSocketDisconnect(String printerId, String message) {
        record(SystemEventType.SOCKET_DISCONNECT, null, printerId, message == null ? "Printer socket disconnected" : message,
                Map.of());
    }

    public void recordRetryRecovery(String jobId, String printerId, String message) {
        record(SystemEventType.RETRY_RECOVERY, jobId, printerId, message == null ? "Job scheduled for retry" : message,
                Map.of());
    }

    public List<SystemEvent> getEvents() {
        return List.copyOf(events);
    }

    public List<SystemEvent> getEventsForJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return List.of();
        }
        return events.stream()
                .filter(event -> jobId.equals(event.getJobId()))
                .toList();
    }

    public long countByType(SystemEventType type) {
        return events.stream().filter(event -> type == event.getType()).count();
    }

    public void clear() {
        events.clear();
        persist();
    }

    private void load() {
        if (Files.notExists(storagePath)) {
            return;
        }
        try {
            if (Files.size(storagePath) == 0) {
                return;
            }
            List<SystemEvent> persisted = objectMapper.readValue(storagePath.toFile(), EVENT_LIST_TYPE);
            if (persisted != null) {
                events.clear();
                events.addAll(persisted);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not load server events from storage: " + storagePath, e);
        }
    }

    private void persist() {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = Files.createTempFile(parent == null ? Path.of(".") : parent, "printflow-events", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), new ArrayList<>(events));
            try {
                Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist server events to storage: " + storagePath, e);
        }
    }
}
