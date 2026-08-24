package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;

import java.time.Instant;

public record PrinterAssignment(
        PrintJob job,
        String printerId,
        String printerName,
        Instant assignedAt
) {
    public PrinterAssignment(PrintJob job, String printerId, String printerName) {
        this(job, printerId, printerName, Instant.now());
    }
}
