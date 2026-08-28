package com.printflow.server.events;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SystemEvent {
    private SystemEventType type;
    private String label;
    private String jobId;
    private String printerId;
    private String message;
    private Map<String, Object> details = new LinkedHashMap<>();
    private Instant createdAt = Instant.now();

    public SystemEvent() {
    }

    public SystemEvent(SystemEventType type, String label, String jobId, String printerId, String message,
                       Map<String, Object> details, Instant createdAt) {
        this.type = type;
        this.label = label;
        this.jobId = jobId;
        this.printerId = printerId;
        this.message = message;
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public SystemEventType getType() {
        return type;
    }

    public void setType(SystemEventType type) {
        this.type = type;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getPrinterId() {
        return printerId;
    }

    public void setPrinterId(String printerId) {
        this.printerId = printerId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
