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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PrintJobService {

    private final PrintJobRepository repository;
    private final Dispatcher dispatcher;

    public PrintJobService(PrintJobRepository repository, Dispatcher dispatcher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
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

        return PrintJobResponse.from(job);
    }

    public PrintJobResponse getJob(String id) {
        PrintJob job = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Print job not found: " + id));

        return PrintJobResponse.from(job);
    }

    public List<PrintJobResponse> listJobs(String userId) {
        if (userId == null || userId.isBlank()) {
            return repository.findAll().stream()
                    .map(PrintJobResponse::from)
                    .toList();
        }

        return repository.findByUserId(userId).stream()
                .map(PrintJobResponse::from)
                .toList();
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