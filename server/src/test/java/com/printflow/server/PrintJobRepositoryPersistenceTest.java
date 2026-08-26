package com.printflow.server;

import com.printflow.server.repository.PrintJobRepository;
import com.printflow.sharedmodel.model.PrinterProfile;
import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrintJobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PrintJobRepositoryPersistenceTest {

    @Test
    void jobsArePersistedAndRestoredAcrossRepositoryInstances(@TempDir Path tempDir) {
        Path storagePath = tempDir.resolve("printflow-jobs.json");

        PrintJobRepository firstRepository = new PrintJobRepository(storagePath);
        PrintJob job = new PrintJob(
                "persisted-job-1",
                "persisted.pdf",
                new PrinterProfile("profile-a", "Office", "A4", "COLOR", false),
                2,
                "tester"
        );
        job.transitionTo(PrintJobStatus.QUEUED);
        job.transitionTo(PrintJobStatus.ASSIGNED);
        job.setAssignedPrinterId("printer-42");
        firstRepository.save(job);

        PrintJobRepository secondRepository = new PrintJobRepository(storagePath);
        PrintJob restored = secondRepository.findById(job.getId()).orElseThrow();

        assertEquals(PrintJobStatus.ASSIGNED, restored.getStatus());
        assertEquals("printer-42", restored.getAssignedPrinterId());
        assertEquals("persisted.pdf", restored.getFileReference());
        assertEquals(1, secondRepository.findAll().size());
    }
}
