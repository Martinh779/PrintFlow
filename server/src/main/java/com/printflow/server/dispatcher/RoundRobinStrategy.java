package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinStrategy implements DispatchStrategy {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Optional<Dispatcher.PrinterRegistration> select(
            List<Dispatcher.PrinterRegistration> printers,
            PrintJob job
    ) {
        if (printers == null || printers.isEmpty()) {
            return Optional.empty();
        }

        List<Dispatcher.PrinterRegistration> activePrinters = printers.stream()
                .filter(Dispatcher.PrinterRegistration::isOnline)
                .filter(p -> p.supportsProfile(job.getProfile()))
                .toList();

        if (activePrinters.isEmpty()) {
            return Optional.empty();
        }

        int index = Math.floorMod(counter.getAndIncrement(), activePrinters.size());
        return Optional.of(activePrinters.get(index));
    }
}
