package com.printflow.server;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.dispatcher.RoundRobinStrategy;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryAndCancellationTest {

    @Test
    void queuedJobsCanBeCancelledAndDoNotRemainDispatchable() {
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy());
        PrintJobRepository repository = new PrintJobRepository();
        ApplicationEventPublisher publisher = event -> { };
        PrintJobService printJobService = new PrintJobService(repository, dispatcher, publisher);

        PrintJob job = new PrintJob(
                "cancelled-job-1",
                "cancelled.pdf",
                new PrinterProfile("profile-a", "Office", "A4", "COLOR", false),
                3,
                "tester"
        );

        repository.save(job);
        dispatcher.enqueue(job);
        assertEquals(PrintJobStatus.QUEUED, job.getStatus());

        dispatcher.cancelQueuedJob(job.getId());

        assertEquals(PrintJobStatus.CANCELLED, job.getStatus());
        assertEquals(0, dispatcher.queueSize());
        assertTrue(dispatcher.dispatchNext().isEmpty());
    }

    @Test
    void brokenPrinterRecoveryReturnsAssignedJobToQueue() {
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy());
        PrintJobRepository repository = new PrintJobRepository();
        ApplicationEventPublisher publisher = event -> { };
        PrintJobService printJobService = new PrintJobService(repository, dispatcher, publisher);

        PrintJob job = new PrintJob(
                "recovery-job-1",
                "recovery.pdf",
                new PrinterProfile("profile-b", "Office", "A4", "BW", true),
                5,
                "tester"
        );
        job.transitionTo(PrintJobStatus.QUEUED);
        job.transitionTo(PrintJobStatus.ASSIGNED);
        job.setAssignedPrinterId("broken-printer");
        repository.save(job);

        printJobService.recoverJobsForPrinter("broken-printer");
        printJobService.recoverJobsForPrinter("broken-printer");

        PrintJob recovered = repository.findById(job.getId()).orElseThrow();
        assertEquals(PrintJobStatus.QUEUED, recovered.getStatus());
        assertNull(recovered.getAssignedPrinterId());
        long queuedCopies = dispatcher.getQueueSnapshot().stream()
                .filter(item -> job.getId().equals(item.getId()))
                .count();
        assertEquals(1, queuedCopies, "Recovered job should only be queued once");
        assertEquals("Printer disconnected; job returned to queue for retry", recovered.getErrorMessage());
    }
}
