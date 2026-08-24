package com.printflow.sharedmodel.dto;

import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrintResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintResultResponse {
    private String jobId;
    private String printerId;
    private Duration duration;
    private Instant completedAt;
    private boolean successful;
    private String message;

    public static PrintResultResponse from(PrintJob job) {
        PrintResult result = job.getResult();

        if (result == null) {
            return new PrintResultResponse(
                    job.getId(),
                    job.getAssignedPrinterId(),
                    null,
                    job.getCompletedAt(),
                    job.getStatus() == PrintJobStatus.COMPLETED,
                    job.getErrorMessage()
            );
        }

        return new PrintResultResponse(
                job.getId(),
                result.getPrinterId(),
                result.getDuration(),
                result.getCompletedAt(),
                result.isSuccessful(),
                result.getMessage()
        );
    }
}
