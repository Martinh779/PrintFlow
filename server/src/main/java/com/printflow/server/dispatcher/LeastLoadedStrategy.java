package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class LeastLoadedStrategy implements DispatchStrategy {

    @Override
    public Optional<Dispatcher.PrinterRegistration> select(List<Dispatcher.PrinterRegistration> printers, PrintJob job) {
        if (printers == null || printers.isEmpty()) {
            return Optional.empty();
        }

        return printers.stream()
                .filter(Objects::nonNull)
                .filter(Dispatcher.PrinterRegistration::isOnline)
                .filter(p -> p.supportsProfile(job.getProfile()))
                .min(Comparator.comparingInt(Dispatcher.PrinterRegistration::getActiveAssignments));
    }
}
