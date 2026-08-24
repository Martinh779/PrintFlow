package com.printflow.sharedmodel.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrintJobStatusMachineTest {

    @Test
    void validTransitionsAreAccepted() {
        PrintJob job = new PrintJob("job-1", "file.pdf", new PrinterProfile("p1", "Office", "A4", "COLOR", false), 10);

        job.transitionTo(PrintJobStatus.QUEUED);
        assertEquals(PrintJobStatus.QUEUED, job.getStatus());

        job.transitionTo(PrintJobStatus.ASSIGNED);
        assertEquals(PrintJobStatus.ASSIGNED, job.getStatus());

        job.transitionTo(PrintJobStatus.PRINTING);
        assertEquals(PrintJobStatus.PRINTING, job.getStatus());

        job.transitionTo(PrintJobStatus.COMPLETED);
        assertEquals(PrintJobStatus.COMPLETED, job.getStatus());
    }

    @Test
    void invalidTransitionsAreRejected() {
        PrintJob job = new PrintJob("job-2", "file.pdf", new PrinterProfile("p2", "Office", "A4", "BW", true), 5);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> job.transitionTo(PrintJobStatus.COMPLETED)
        );

        assertTrue(ex.getMessage().contains("Invalid status transition"));
    }

    @Test
    void terminalStateProtectionIsEnforced() {
        PrintJob job = new PrintJob("job-3", "file.pdf", new PrinterProfile("p3", "Office", "A4", "COLOR", false), 1);
        job.setStatus(PrintJobStatus.COMPLETED);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> job.transitionTo(PrintJobStatus.FAILED)
        );

        assertTrue(ex.getMessage().contains("terminal"));
    }

    @Test
    void cancellationRulesAreEnforced() {
        PrintJob queued = new PrintJob("job-4", "file.pdf", new PrinterProfile("p4", "Office", "A4", "COLOR", false), 1);
        queued.setStatus(PrintJobStatus.QUEUED);

        queued.cancel();
        assertEquals(PrintJobStatus.CANCELLED, queued.getStatus());

        PrintJob printing = new PrintJob("job-5", "file.pdf", new PrinterProfile("p5", "Office", "A4", "COLOR", false), 1);
        printing.setStatus(PrintJobStatus.PRINTING);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                printing::cancel
        );
        assertTrue(ex.getMessage().contains("Cannot cancel while printing"));
    }
}