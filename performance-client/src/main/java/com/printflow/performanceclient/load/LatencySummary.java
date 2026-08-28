package com.printflow.performanceclient.load;

public record LatencySummary(
        long minMs,
        long maxMs,
        double avgMs,
        long p50Ms,
        long p95Ms
) {
    public static LatencySummary empty() {
        return new LatencySummary(0, 0, 0.0, 0, 0);
    }
}
