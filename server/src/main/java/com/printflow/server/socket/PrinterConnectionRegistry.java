package com.printflow.server.socket;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active TCP connections from printers. When used by Dispatcher (via DI)
 * it allows the dispatcher to consider only printers with an active socket
 * connection as available for assignment. If Dispatcher was constructed
 * without a registry (e.g., in unit tests) it will fall back to permissive
 * behavior and ignore this registry.
 */
@Component
public class PrinterConnectionRegistry {

    private final Set<String> connected = ConcurrentHashMap.newKeySet();

    public void addConnection(String printerId) {
        if (printerId != null) connected.add(printerId);
    }

    public void removeConnection(String printerId) {
        if (printerId != null) connected.remove(printerId);
    }

    public boolean hasConnection(String printerId) {
        if (printerId == null) return false;
        return connected.contains(printerId);
    }

    public Set<String> getConnected() {
        return Set.copyOf(connected);
    }
}
