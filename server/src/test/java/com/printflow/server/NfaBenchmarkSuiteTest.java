package com.printflow.server;

import com.printflow.server.dispatcher.Dispatcher;
import com.printflow.server.dispatcher.PrinterAssignment;
import com.printflow.server.dispatcher.RoundRobinStrategy;
import com.printflow.server.events.ServerEventLogger;
import com.printflow.server.repository.PrintJobRepository;
import com.printflow.server.service.PrintJobService;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import com.printflow.sharedmodel.model.PrinterProfile;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class NfaBenchmarkSuiteTest {

    @Test
    void concurrentDispatchKeepsAssignmentsUniqueAndComplete() throws InterruptedException {
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy());
        dispatcher.registerPrinter("printer-1", "Printer 1", true);
        dispatcher.registerPrinter("printer-2", "Printer 2", true);
        dispatcher.registerPrinter("printer-3", "Printer 3", true);
        dispatcher.registerPrinter("printer-4", "Printer 4", true);

        int jobCount = 200;
        List<PrintJob> jobs = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            jobs.add(new PrintJob(
                    "nfa-job-" + i,
                    "nfa-file-" + i + ".pdf",
                    new PrinterProfile("nfa-profile", "A4", "A4", "BW", false),
                    5
            ));
        }
        jobs.parallelStream().forEach(dispatcher::enqueue);

        Set<String> assignedJobIds = ConcurrentHashMap.newKeySet();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Thread thread = new Thread(() -> {
                while (true) {
                    Optional<PrinterAssignment> assignment = dispatcher.dispatchNext();
                    if (assignment.isEmpty()) {
                        break;
                    }
                    assignedJobIds.add(assignment.get().job().getId());
                }
            });
            workers.add(thread);
            thread.start();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(jobCount, assignedJobIds.size(), "Every queued job should be assigned exactly once");
        assertEquals(0, dispatcher.queueSize(), "No jobs should be left behind in the queue");
    }

    @Test
    void completionStatusIsVisibleWithinTwoSeconds() throws Exception {
        Path tempDir = Files.createTempDirectory("printflow-nfa-status");
        PrintJobRepository repository = new PrintJobRepository(tempDir.resolve("jobs.json"));
        ServerEventLogger eventLogger = new ServerEventLogger(tempDir.resolve("events.json"));
        Dispatcher dispatcher = new Dispatcher(new RoundRobinStrategy(), null, eventLogger);
        ApplicationEventPublisher publisher = event -> { };
        PrintJobService service = new PrintJobService(repository, dispatcher, publisher, eventLogger);

        CreatePrintJobRequest request = new CreatePrintJobRequest(
                "status-latency.pdf",
                new PrinterProfile("status-profile", "A4", "A4", "BW", false),
                2,
                "nfa-tester"
        );
        String jobId = service.createJob(request).getId();
        PrintJob queuedJob = repository.findById(jobId).orElseThrow();
        queuedJob.transitionTo(PrintJobStatus.ASSIGNED);
        queuedJob.setAssignedPrinterId("printer-1");
        repository.save(queuedJob);

        Instant completionSignalAt = Instant.now();
        service.updateStatusFromPrinter(jobId, "printer-1", "PRINTING", "started", 0, true);
        service.updateStatusFromPrinter(jobId, "printer-1", "COMPLETED", "ok", 120, true);

        PrintJob job = repository.findById(jobId).orElseThrow();
        assertEquals(PrintJobStatus.COMPLETED, job.getStatus());
        assertNotNull(job.getCompletedAt());
        assertTrue(Duration.between(completionSignalAt, job.getCompletedAt()).toMillis() <= 2000,
                "Status update should be visible within two seconds");
    }

    @Test
    void serverStartsAndExposesHealthWithinFifteenSeconds() throws Exception {
        Path tempDir = Files.createTempDirectory("printflow-nfa-startup");
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("printflow.socket.port", "0");
        properties.put("printflow.storage.jobs.file", tempDir.resolve("jobs.json").toString());
        properties.put("printflow.storage.events.file", tempDir.resolve("events.json").toString());
        properties.put("logging.level.root", "ERROR");

        SpringApplication app = new SpringApplication(ServerApplication.class);
        app.setDefaultProperties(properties);

        long startNanos = System.nanoTime();
        try (ConfigurableApplicationContext context = app.run()) {
            long startupMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            assertTrue(startupMs < 15_000, "Server startup exceeded 15 seconds: " + startupMs + " ms");

            Integer localPort = context.getEnvironment().getProperty("local.server.port", Integer.class);
            assertNotNull(localPort, "Local server port should be available");

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> healthResponse = restTemplate.getForEntity(
                    "http://localhost:" + localPort + "/actuator/health",
                    Map.class
            );
            assertTrue(healthResponse.getStatusCode().is2xxSuccessful(), "Health endpoint should be reachable");
        }
    }
}
