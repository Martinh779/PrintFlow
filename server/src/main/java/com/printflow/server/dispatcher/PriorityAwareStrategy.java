package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrinterProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class PriorityAwareStrategy implements DispatchStrategy {

    private static final int HIGH_PRIORITY_THRESHOLD = 7;
    private static final int MEDIUM_PRIORITY_THRESHOLD = 4;

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Optional<Dispatcher.PrinterRegistration> select(List<Dispatcher.PrinterRegistration> printers, PrintJob job) {
        if (printers == null || printers.isEmpty()) {
            return Optional.empty();
        }

        PrinterProfile requestedProfile = job == null ? null : job.getProfile();
        List<Dispatcher.PrinterRegistration> candidates = printers.stream()
                .filter(Dispatcher.PrinterRegistration::isOnline)
                .filter(printer -> printer.supportsProfile(requestedProfile))
                .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        int priority = job != null && job.getPriority() != null ? job.getPriority() : 1;
        Comparator<Dispatcher.PrinterRegistration> byMatchThenLoad = Comparator
                .comparingInt((Dispatcher.PrinterRegistration printer) -> printer.profileMatchSpecificity(requestedProfile))
                .thenComparingInt(Dispatcher.PrinterRegistration::getActiveAssignments)
                .thenComparing(Dispatcher.PrinterRegistration::getId);

        if (priority >= HIGH_PRIORITY_THRESHOLD) {
            return candidates.stream().min(byMatchThenLoad);
        }

        if (priority >= MEDIUM_PRIORITY_THRESHOLD) {
            return candidates.stream()
                    .min(Comparator.comparingInt(Dispatcher.PrinterRegistration::getActiveAssignments)
                            .thenComparingInt(printer -> printer.profileMatchSpecificity(requestedProfile))
                            .thenComparing(Dispatcher.PrinterRegistration::getId));
        }

        List<Dispatcher.PrinterRegistration> stableOrder = candidates.stream()
                .sorted(Comparator.comparing(Dispatcher.PrinterRegistration::getId))
                .toList();
        int index = Math.floorMod(counter.getAndIncrement(), stableOrder.size());
        return Optional.of(stableOrder.get(index));
    }
}
