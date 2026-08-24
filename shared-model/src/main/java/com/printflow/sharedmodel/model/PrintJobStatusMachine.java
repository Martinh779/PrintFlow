package com.printflow.sharedmodel.model;

import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

import static lombok.AccessLevel.PRIVATE;

@NoArgsConstructor(access = PRIVATE)
public final class PrintJobStatusMachine {

    public static boolean canTransition(PrintJobStatus from, PrintJobStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return from.canTransitionTo(to);
    }

    public static void transition(PrintJob job, PrintJobStatus targetStatus) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");

        PrintJobStatus currentStatus = job.getStatus();

        if (currentStatus == null) {
            throw new IllegalStateException("Job status is missing");
        }

        if (currentStatus.isTerminal()) {
            throw new IllegalStateException("Cannot transition terminal job from " + currentStatus + " to " + targetStatus);
        }

        if (targetStatus == PrintJobStatus.CANCELLED && currentStatus == PrintJobStatus.PRINTING) {
            throw new IllegalStateException("Cannot cancel a print job while it is printing");
        }

        if (!canTransition(currentStatus, targetStatus)) {
            throw new IllegalStateException("Invalid status transition: " + currentStatus + " -> " + targetStatus);
        }

        job.setStatus(targetStatus);
        Instant now = Instant.now();

        switch (targetStatus) {
            case QUEUED -> job.setQueuedAt(now);
            case ASSIGNED -> job.setAssignedAt(now);
            case PRINTING -> job.setStartedAt(now);
            case COMPLETED -> {
                job.setCompletedAt(now);
                if (job.getResult() == null) {
                    job.setResult(new PrintResult(
                            job.getAssignedPrinterId(),
                            null,
                            now,
                            true,
                            "Completed successfully"
                    ));
                }
            }
            case CANCELLED -> {
                job.setCompletedAt(now);
                job.setErrorMessage("Cancelled");
            }
            case FAILED -> {
                job.setCompletedAt(now);
                if (job.getErrorMessage() == null || job.getErrorMessage().isBlank()) {
                    job.setErrorMessage("Failed");
                }
            }
            default -> { }
        }
    }

    public static void cancel(PrintJob job) {
        Objects.requireNonNull(job, "job must not be null");

        PrintJobStatus currentStatus = job.getStatus();

        if (currentStatus == null) {
            throw new IllegalStateException("Job status is missing");
        }

        if (currentStatus.isTerminal()) {
            throw new IllegalStateException("Cannot cancel a terminal job in status " + currentStatus);
        }

        if (currentStatus == PrintJobStatus.PRINTING) {
            throw new IllegalStateException("Cannot cancel while printing");
        }

        if (!currentStatus.canTransitionTo(PrintJobStatus.CANCELLED)) {
            throw new IllegalStateException("Invalid cancellation from status " + currentStatus);
        }

        transition(job, PrintJobStatus.CANCELLED);
    }
}