package com.printflow.performanceclient.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LatencyCalculator {

    private LatencyCalculator() {
    }

    public static LatencySummary summarize(List<Long> samplesMs) {
        if (samplesMs == null || samplesMs.isEmpty()) {
            return LatencySummary.empty();
        }

        List<Long> sorted = new ArrayList<>(samplesMs);
        Collections.sort(sorted);

        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        return new LatencySummary(min, max, avg, p50, p95);
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        int boundedIndex = Math.min(Math.max(index, 0), sorted.size() - 1);
        return sorted.get(boundedIndex);
    }
}
