package com.printflow.server;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.server.socket.PrinterConnectionRegistry;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import com.printflow.sharedmodel.model.PrinterProfile;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PrintJobLifecycleTest {

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private PrintJobService printJobService;

    @Autowired
    private PrintJobRepository repository;

    @Autowired
    private PrinterConnectionRegistry connectionRegistry;

    @Test
    void createJobAndMarkCompletedViaPrinterStatus() {
        // isolate from the app-default printer bootstrap used by the test environment.
        try {
            dispatcher.setPrinterOnline("printer-001", false);
        } catch (IllegalArgumentException ignored) {
            // no default printer exists in this test context
        }
        connectionRegistry.removeConnection("printer-001");

        // register a printer (online) and mark its socket connection as active so dispatcher can assign jobs to it.
        dispatcher.registerPrinter(new Dispatcher.PrinterRegistration("test-printer-2", "Test Printer 2", true));
        connectionRegistry.addConnection("test-printer-2");

        CreatePrintJobRequest req = new CreatePrintJobRequest(
                "file-to-print.pdf",
                new PrinterProfile("default-profile", "A4 Color", "A4", "COLOR", false),
                1,
                "tester"
        );

        var response = printJobService.createJob(req);
        assertNotNull(response);
        String jobId = response.getId();
        assertNotNull(jobId);

        // ensure the job was assigned (some other tests may register printers too)
        PrintJob job = repository.findById(jobId).orElseThrow();
        assertEquals(PrintJobStatus.ASSIGNED, job.getStatus(), "Job should have been assigned to a registered printer");
        assertNotNull(job.getAssignedPrinterId(), "Assigned printer ID should be set after dispatch");

        // simulate the printer reporting completion using the actual assigned printer id
        String assignedPrinter = job.getAssignedPrinterId();
        // simulate printer lifecycle: printing then completed
        printJobService.updateStatusFromPrinter(jobId, assignedPrinter, "PRINTING", "started", 10L, true);
        printJobService.updateStatusFromPrinter(jobId, assignedPrinter, "COMPLETED", "done", 250L, true);

        // verify job is completed and has a result
        job = repository.findById(jobId).orElseThrow();
        assertEquals(PrintJobStatus.COMPLETED, job.getStatus(), "Job should be completed after printer reports completion");
        assertNotNull(job.getResult(), "Completed job must have a PrintResult");
        assertTrue(job.getResult().isSuccessful(), "Print result should indicate success");
        assertEquals(assignedPrinter, job.getAssignedPrinterId(), "Assigned printer ID should remain set");
    }

    @Test
    void failedPrinterAssignmentIsRequeuedForAnotherPrinter() {
        try {
            dispatcher.setPrinterOnline("printer-001", false);
        } catch (IllegalArgumentException ignored) {
            // no default printer exists in this test context
        }
        connectionRegistry.removeConnection("printer-001");

        dispatcher.registerPrinter(new Dispatcher.PrinterRegistration("printer-a", "Printer A", true));
        dispatcher.registerPrinter(new Dispatcher.PrinterRegistration("printer-b", "Printer B", true));
        connectionRegistry.addConnection("printer-a");
        connectionRegistry.addConnection("printer-b");

        CreatePrintJobRequest req = new CreatePrintJobRequest(
                "retry-file.pdf",
                new PrinterProfile("default-profile", "A4 Color", "A4", "COLOR", false),
                1,
                "tester"
        );

        String jobId = printJobService.createJob(req).getId();
        PrintJob job = repository.findById(jobId).orElseThrow();
        assertEquals(PrintJobStatus.ASSIGNED, job.getStatus());
        String firstPrinter = job.getAssignedPrinterId();
        assertNotNull(firstPrinter);

        printJobService.updateStatusFromPrinter(jobId, firstPrinter, "FAILED", "offline", 100L, false);

        job = repository.findById(jobId).orElseThrow();
        assertEquals(PrintJobStatus.ASSIGNED, job.getStatus(), "Failed assigned job should be immediately retried on another printer");
        assertNotNull(job.getAssignedPrinterId(), "A replacement printer assignment should be created");
        assertNotEquals(firstPrinter, job.getAssignedPrinterId(), "Job should move off the failed printer");

        var retryAssignment = dispatcher.dispatchNext();
        assertTrue(retryAssignment.isEmpty() || !firstPrinter.equals(retryAssignment.get().printerId()), "No further retry should keep the failed printer");
    }
}
