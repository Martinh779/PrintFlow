package com.printflow.sharedmodel.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintJob {
    private String id;
    private String fileReference;
    private PrinterProfile profile;
    private Integer priority;
    private PrintJobStatus status = PrintJobStatus.CREATED;
    private Instant createdAt = Instant.now();
    private Instant queuedAt;
    private Instant assignedAt;
    private Instant startedAt;
    private Instant completedAt;
    private String assignedPrinterId;
    private PrintResult result;
    private String errorMessage;

    public PrintJob(String id, String fileReference, PrinterProfile profile, Integer priority) {
        this();
        this.id = id;
        this.fileReference = fileReference;
        this.profile = profile;
        this.priority = priority;
    }

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