package com.printflow.server;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.dispatcher.RoundRobinStrategy;
import com.printflow.server.events.ServerEventLogger;
import com.printflow.server.events.SystemEventType;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import com.printflow.sharedmodel.dto.PrintJobResponse;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerEventLoggingTest {

    @Test
    void logsCriticalLifecycleAndRecoveryEvents() throws Exception {
        Path tempDir = Files.createTempDirectory("printflow-event-log");
        Path eventPath = tempDir.resolve("events.json");
        Path jobPath = tempDir.resolve("jobs.json");

        ServerEventLogger eventLogger = new ServerEventLogger(eventPath);
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy(), null, eventLogger);
        PrintJobRepository repository = new PrintJobRepository(jobPath);
        ApplicationEventPublisher publisher = event -> { };
        PrintJobService service = new PrintJobService(repository, dispatcher, publisher, eventLogger);

        CreatePrintJobRequest request = new CreatePrintJobRequest(
                "test-job.pdf",
                new PrinterProfile("profile-a", "Office", "A4", "COLOR", false),
                3,
                "tester"
        );

        PrintJobResponse created = service.createJob(request);
        assertEquals(1, eventLogger.countByType(SystemEventType.JOB_CREATED));
        assertEquals(1, eventLogger.countByType(SystemEventType.JOB_QUEUED));

        var job = repository.findById(created.getId()).orElseThrow();
        job.transitionTo(com.printflow.sharedmodel.model.PrintJobStatus.ASSIGNED);
        job.setAssignedPrinterId("printer-1");
        repository.save(job);

        job.transitionTo(com.printflow.sharedmodel.model.PrintJobStatus.PRINTING);
        repository.save(job);

        service.updateStatusFromPrinter(created.getId(), "printer-1", "COMPLETED", "Printed successfully", 120, true);
        assertTrue(eventLogger.getEventsForJob(created.getId()).stream()
                .anyMatch(event -> event.getType() == SystemEventType.JOB_COMPLETED));

        var recoveryJob = new com.printflow.sharedmodel.model.PrintJob(
                "recovery-job",
                "recovery.pdf",
                new PrinterProfile("profile-recovery", "Office", "A4", "COLOR", false),
                4,
                "tester"
        );
        recoveryJob.transitionTo(com.printflow.sharedmodel.model.PrintJobStatus.QUEUED);
        recoveryJob.transitionTo(com.printflow.sharedmodel.model.PrintJobStatus.ASSIGNED);
        recoveryJob.setAssignedPrinterId("printer-1");
        repository.save(recoveryJob);

        service.recoverJobsForPrinter("printer-1");
        assertTrue(eventLogger.getEvents().stream()
                .anyMatch(event -> event.getType() == SystemEventType.RETRY_RECOVERY
                        && "printer-1".equals(event.getPrinterId())
                        && "recovery-job".equals(event.getJobId())));

        CreatePrintJobRequest secondRequest = new CreatePrintJobRequest(
                "cancelled-job.pdf",
                new PrinterProfile("profile-b", "Office", "A4", "BW", true),
                2,
                "tester"
        );
        PrintJobResponse cancelled = service.createJob(secondRequest);
        service.cancelJob(cancelled.getId());

        assertTrue(eventLogger.getEvents().stream()
                .anyMatch(event -> event.getType() == SystemEventType.JOB_CANCELLED
                        && cancelled.getId().equals(event.getJobId())));
    }
}
