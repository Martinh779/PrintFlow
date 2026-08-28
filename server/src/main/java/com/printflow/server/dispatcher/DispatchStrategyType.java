package com.printflow.server.dispatcher;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum DispatchStrategyType {
    ROUND_ROBIN("round-robin", "Round Robin"),
    LEAST_LOADED("least-loaded", "Least Loaded"),
    PRIORITY_AWARE("priority-aware", "Priority Aware");

    private final String value;
    private final String label;

    DispatchStrategyType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    public static Optional<DispatchStrategyType> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.value.equals(normalized)
                        || type.name().toLowerCase(Locale.ROOT).equals(normalized.replace('-', '_'))
                        || (type == LEAST_LOADED && ("leastloaded".equals(normalized) || "least-loaded-first".equals(normalized))))
                .findFirst();
    }
}
