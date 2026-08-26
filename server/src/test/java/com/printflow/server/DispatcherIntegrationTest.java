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
public class DispatcherIntegrationTest {

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private PrintJobService printJobService;

    @Autowired
    private PrintJobRepository repository;

    @Autowired
    private PrinterConnectionRegistry connectionRegistry;

    @Test
    void printerRegistrationAllowsQueuedJobToBeAssigned() {
        // isolate the test from the app-level printer bootstrap by disabling the default connected printer.
        try {
            dispatcher.setPrinterOnline("printer-001", false);
        } catch (IllegalArgumentException ignored) {
            // no default printer has been registered in this test context
        }
        connectionRegistry.removeConnection("printer-001");

        // register a printer (online) and mark its socket connection as active so dispatcher will consider it for assignment.
        dispatcher.registerPrinter(new Dispatcher.PrinterRegistration("test-printer", "Test Printer", true));
        connectionRegistry.addConnection("test-printer");

        // create a print job request
        CreatePrintJobRequest req = new CreatePrintJobRequest(
                "test-file.pdf",
                new PrinterProfile("default-profile", "A4 Color", "A4", "COLOR", false),
                1,
                "tester"
        );

        var response = printJobService.createJob(req);
        assertNotNull(response);
        String jobId = response.getId();
        assertNotNull(jobId);

        // fetch job from repository
        PrintJob job = repository.findById(jobId).orElseThrow();
        assertEquals(PrintJobStatus.ASSIGNED, job.getStatus(), "Job should have been assigned to the registered printer");
        assertEquals("test-printer", job.getAssignedPrinterId(), "Assigned printer ID should match the registered printer");
    }
}
