package com.printflow.server.service;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.exception.BadRequestException;
import com.printflow.server.exception.InvalidJobStateException;
import com.printflow.server.exception.ResourceNotFoundException;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import com.printflow.sharedmodel.dto.PrintJobResponse;
import com.printflow.sharedmodel.dto.PrintResultResponse;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrintResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PrintJobService {

    private final PrintJobRepository repository;
    private final Dispatcher dispatcher;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public PrintJobService(PrintJobRepository repository, Dispatcher dispatcher, org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.eventPublisher = eventPublisher;
    }

    public PrintJobResponse createJob(CreatePrintJobRequest request) {
        validateRequest(request);

        PrintJob job = new PrintJob(
                UUID.randomUUID().toString(),
                request.getFileReference(),
                request.getProfile(),
                request.getPriority(),
                request.getUserId()
        );
        job.transitionTo(PrintJobStatus.QUEUED);
        repository.save(job);
        dispatcher.enqueue(job);
        // notify listeners (e.g., TcpPrinterServer) that a job is enqueued so it can attempt dispatch
        eventPublisher.publishEvent(new JobEnqueuedEvent(job.getId()));

        return PrintJobResponse.from(job);
    }

    public void updateStatusFromPrinter(String jobId, String printerId, String status, String detail, long durationMs, boolean successful) {
        PrintJob job = repository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Print job not found: " + jobId));

        String normalized = status == null ? "" : status.trim().toUpperCase();
        switch (normalized) {
            case "DRUCKT", "PRINTING" -> {
                if (job.getStatus() == PrintJobStatus.ASSIGNED || job.getStatus() == PrintJobStatus.QUEUED) {
                    job.transitionTo(PrintJobStatus.PRINTING);
                }
            }
            case "ABGESCHLOSSEN", "COMPLETED" -> {
                if (job.isTerminal()) {
                    break;
                }
                if (job.getStatus() != PrintJobStatus.COMPLETED) {
                    job.transitionTo(PrintJobStatus.COMPLETED);
                }
                job.setAssignedPrinterId(printerId);
                job.setResult(new PrintResult(
                        printerId,
                        Duration.ofMillis(durationMs),
                        Instant.now(),
                        successful,
                        detail == null ? "Completed successfully" : detail
                ));
            }
            case "FEHLGESCHLAGEN", "FAILED" -> handlePrinterFailure(job, printerId, detail, durationMs);
            default -> { }
        }

        repository.save(job);
    }

    public void recoverJobsForPrinter(String printerId) {
        if (printerId == null || printerId.isBlank()) {
            return;
        }

        List<PrintJob> affectedJobs = repository.findAll().stream()
                .filter(job -> printerId.equals(job.getAssignedPrinterId()) && !job.isTerminal())
                .toList();

        for (PrintJob job : affectedJobs) {
            if (job.getStatus() == PrintJobStatus.CANCELLED || job.isTerminal()) {
                continue;
            }
            if (job.getStatus() == PrintJobStatus.ASSIGNED || job.getStatus() == PrintJobStatus.PRINTING) {
                job.setErrorMessage("Printer disconnected; job returned to queue for retry");
                dispatcher.unassignJob(job);
                eventPublisher.publishEvent(new JobEnqueuedEvent(job.getId()));
                repository.save(job);
            }
        }
    }

    private void handlePrinterFailure(PrintJob job, String printerId, String detail, long durationMs) {
        if (job == null || job.isTerminal()) {
            return;
        }

        String errorMessage = detail == null || detail.isBlank() ? "Print failed" : detail;
        job.setErrorMessage(errorMessage);

        try {
            dispatcher.setPrinterOnline(printerId, false);
        } catch (IllegalArgumentException ignored) {
            // The printer may already be removed; retry elsewhere without failing the whole flow.
        }

        if (job.getStatus() == PrintJobStatus.ASSIGNED || job.getStatus() == PrintJobStatus.PRINTING) {
            dispatcher.unassignJob(job);
            eventPublisher.publishEvent(new JobEnqueuedEvent(job.getId()));
            return;
        }

        job.transitionTo(PrintJobStatus.FAILED);
        job.setAssignedPrinterId(printerId);
        job.setResult(new PrintResult(
                printerId,
                Duration.ofMillis(durationMs),
                Instant.now(),
                false,
                errorMessage
        ));
    }

    public PrintJobResponse getJob(String id) {
        PrintJob job = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Print job not found: " + id));
        return PrintJobResponse.from(job);
    }

    public List<PrintJobResponse> listJobs(String userId) {
        if (userId == null || userId.isBlank()) {
            return repository.findAll().stream().map(PrintJobResponse::from).toList();
        }
        return repository.findByUserId(userId).stream().map(PrintJobResponse::from).toList();
    }

    public PrintJobResponse cancelJob(String id) {
        PrintJob job = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Print job not found: " + id));

        if (job.getStatus() == PrintJobStatus.PRINTING) {
            throw new InvalidJobStateException("Cannot cancel a job that is already printing");
        }
        if (!job.getStatus().canTransitionTo(PrintJobStatus.CANCELLED)) {
            throw new InvalidJobStateException("Cannot cancel job in status " + job.getStatus());
        }

        job.cancel();
        dispatcher.cancelQueuedJob(id);
        repository.save(job);

        return PrintJobResponse.from(job);
    }

    public PrintResultResponse getResult(String id) {
        PrintJob job = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Print job not found: " + id));
        if (!job.isTerminal()) {
            throw new InvalidJobStateException("Result is not available until the job is finished");
        }
        return PrintResultResponse.from(job);
    }

    private void validateRequest(CreatePrintJobRequest request) {
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        if (request.getFileReference() == null || request.getFileReference().isBlank()) {
            throw new BadRequestException("fileReference must not be blank");
        }
        if (request.getProfile() == null) {
            throw new BadRequestException("profile must not be null");
        }
        if (request.getPriority() == null || request.getPriority() <= 0) {
            throw new BadRequestException("priority must be a positive integer");
        }
    }
}