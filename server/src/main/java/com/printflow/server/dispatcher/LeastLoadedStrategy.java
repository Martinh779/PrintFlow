package com.printflow.server.dispatcher;

import com.printflow.sharedmodel.model.PrintJob;
import com.printflow.sharedmodel.model.PrinterProfile;

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

        PrinterProfile requestedProfile = job == null ? null : job.getProfile();
        return printers.stream()
                .filter(Objects::nonNull)
                .filter(Dispatcher.PrinterRegistration::isOnline)
                .filter(p -> p.supportsProfile(requestedProfile))
                .min(Comparator.comparingInt(Dispatcher.PrinterRegistration::getActiveAssignments));
    }
}
