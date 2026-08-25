package com.printflow.sharedmodel.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Data
public class PrintJob {
    private String id;
    private String fileReference;
    private PrinterProfile profile;
    private Integer priority;
    private String userId;
    private PrintJobStatus status = PrintJobStatus.CREATED;
    private Instant createdAt = Instant.now();
    private Instant queuedAt;
    private Instant assignedAt;
    private Instant startedAt;
    private Instant completedAt;
    private String assignedPrinterId;
    private PrintResult result;
    private String errorMessage;

    // Explicit no-arg constructor to avoid relying on Lombok during compilation
    public PrintJob() {
        this.status = PrintJobStatus.CREATED;
        this.createdAt = Instant.now();
    }

    public PrintJob(String id, String fileReference, PrinterProfile profile, Integer priority) {
        this();
        this.id = id;
        this.fileReference = fileReference;
        this.profile = profile;
        this.priority = priority;
    }

    public PrintJob(String id, String fileReference, PrinterProfile profile, Integer priority, String userId) {
        this(id, fileReference, profile, priority);
        this.userId = userId;
    }

    // Getters and setters (explicit to ensure compilation without Lombok annotation processing)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFileReference() { return fileReference; }
    public void setFileReference(String fileReference) { this.fileReference = fileReference; }

    public PrinterProfile getProfile() { return profile; }
    public void setProfile(PrinterProfile profile) { this.profile = profile; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public PrintJobStatus getStatus() { return status; }
    public void setStatus(PrintJobStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getQueuedAt() { return queuedAt; }
    public void setQueuedAt(Instant queuedAt) { this.queuedAt = queuedAt; }

    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getAssignedPrinterId() { return assignedPrinterId; }
    public void setAssignedPrinterId(String assignedPrinterId) { this.assignedPrinterId = assignedPrinterId; }

    public PrintResult getResult() { return result; }
    public void setResult(PrintResult result) { this.result = result; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public void transitionTo(PrintJobStatus newStatus) {
        PrintJobStatusMachine.transition(this, newStatus);
    }

    public void cancel() {
        PrintJobStatusMachine.cancel(this);
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}