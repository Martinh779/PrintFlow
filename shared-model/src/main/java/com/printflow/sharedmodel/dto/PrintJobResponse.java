package com.printflow.sharedmodel.dto;

import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintJobResponse {
    private String id;
    private String fileReference;
    private com.printflow.sharedmodel.model.PrinterProfile profile;
    private Integer priority;
    private String userId;
    private PrintJobStatus status;
    private Instant createdAt;
    private Instant queuedAt;
    private Instant assignedAt;
    private Instant startedAt;
    private Instant completedAt;
    private String assignedPrinterId;
    private String errorMessage;

    public static PrintJobResponse from(PrintJob job) {
        return new PrintJobResponse(
                job.getId(),
                job.getFileReference(),
                job.getProfile(),
                job.getPriority(),
                job.getUserId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getQueuedAt(),
                job.getAssignedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getAssignedPrinterId(),
                job.getErrorMessage()
        );
    }
}
