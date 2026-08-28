package com.printflow.performanceclient.load;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyCalculatorTest {

    @Test
    void returnsEmptySummaryForNoSamples() {
        LatencySummary summary = LatencyCalculator.summarize(List.of());
        assertThat(summary.minMs()).isZero();
        assertThat(summary.maxMs()).isZero();
        assertThat(summary.avgMs()).isZero();
        assertThat(summary.p50Ms()).isZero();
        assertThat(summary.p95Ms()).isZero();
    }

    @Test
    void calculatesPercentilesAndAverage() {
        LatencySummary summary = LatencyCalculator.summarize(List.of(10L, 20L, 30L, 40L, 50L));
        assertThat(summary.minMs()).isEqualTo(10);
        assertThat(summary.maxMs()).isEqualTo(50);
        assertThat(summary.avgMs()).isEqualTo(30.0);
        assertThat(summary.p50Ms()).isEqualTo(30);
        assertThat(summary.p95Ms()).isEqualTo(50);
    }
}
