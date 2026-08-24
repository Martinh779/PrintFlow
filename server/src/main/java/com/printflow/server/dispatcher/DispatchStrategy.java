package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;

import java.util.List;
import java.util.Optional;

public interface DispatchStrategy {
    Optional<Dispatcher.PrinterRegistration> select(
            List<Dispatcher.PrinterRegistration> printers,
            PrintJob job
    );
}
