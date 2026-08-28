package com.printflow.server.socket;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrinterProfile;
import com.printflow.sharedmodel.protocol.RegisterPrinterMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "printflow.socket.port=51023",
        "printflow.socket.read-timeout-ms=700",
        "printflow.socket.heartbeat-timeout-ms=900",
        "printflow.socket.heartbeat-check-interval-ms=150"
})
class TcpPrinterServerFailoverTest {

    @Autowired
    private PrintJobService printJobService;

    @Autowired
    private PrintJobRepository repository;

    @Autowired
    private Dispatcher dispatcher;

    @Test
    void unresponsivePrinterConnectionTriggersRecoveryWithoutDuplicateQueueEntries() throws Exception {
        String printerId = "timeout-printer-01";

        try (Socket socket = new Socket("localhost", 51023);
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            RegisterPrinterMessage register = new RegisterPrinterMessage(
                    printerId,
                    "Timeout Printer",
                    "localhost",
                    51023,
                    true
            );
            writer.println(register.toJson());
            writer.flush();

            CreatePrintJobRequest request = new CreatePrintJobRequest(
                    "timeout-retry.pdf",
                    new PrinterProfile("timeout-profile", "A4", "A4", "BW", false),
                    5,
                    "tester"
            );

            String jobId = printJobService.createJob(request).getId();
            PrintJob assigned = awaitStatus(jobId, PrintJobStatus.ASSIGNED, Duration.ofSeconds(2));
            assertEquals(printerId, assigned.getAssignedPrinterId());

            PrintJob recovered = awaitStatus(jobId, PrintJobStatus.QUEUED, Duration.ofSeconds(3));
            assertNull(recovered.getAssignedPrinterId());
            assertEquals("Printer disconnected; job returned to queue for retry", recovered.getErrorMessage());

            long queuedCopies = dispatcher.getQueueSnapshot().stream()
                    .filter(job -> jobId.equals(job.getId()))
                    .count();
            assertEquals(1, queuedCopies);
        }
    }

    private PrintJob awaitStatus(String jobId, PrintJobStatus expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            PrintJob job = repository.findById(jobId).orElseThrow();
            if (job.getStatus() == expected) {
                return job;
            }
            Thread.sleep(50);
        }
        PrintJob finalState = repository.findById(jobId).orElseThrow();
        fail("Expected status " + expected + " but was " + finalState.getStatus());
        return finalState;
    }
}
