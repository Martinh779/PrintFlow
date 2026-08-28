package com.printflow.performanceclient.load;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NfaBenchmarkEvaluatorTest {

    @Test
    void passesWhenAllThresholdsAreMet() {
        List<Map<String, Object>> scenarios = List.of(
                scenario(1, 99.92, 180, 420, 1600, 10.0, 100, 100, 0, 0, 0, 100),
                scenario(2, 99.95, 170, 390, 1500, 16.5, 100, 100, 0, 0, 0, 100),
                scenario(4, 99.97, 165, 460, 1300, 19.2, 100, 100, 0, 0, 0, 100)
        );

        Map<String, Object> result = NfaBenchmarkEvaluator.evaluate(40, scenarios);

        assertThat(result.get("overallPass")).isEqualTo(true);
        assertThat(failedChecks(result)).isEmpty();
    }

    @Test
    void failsOnRestSuccessLatencyAndScalingViolations() {
        List<Map<String, Object>> scenarios = List.of(
                scenario(1, 99.80, 260, 610, 2200, 10.0, 100, 96, 1, 1, 2, 99),
                scenario(2, 99.85, 230, 590, 2100, 13.0, 100, 97, 1, 1, 1, 98),
                scenario(4, 99.90, 220, 540, 2050, 14.5, 100, 98, 1, 1, 0, 100)
        );

        Map<String, Object> result = NfaBenchmarkEvaluator.evaluate(40, scenarios);

        assertThat(result.get("overallPass")).isEqualTo(false);
        assertThat(failedChecks(result)).contains(
                "NFA-01",
                "NFA-02",
                "NFA-03",
                "NFA-04",
                "NFA-05",
                "NFA-06"
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> failedChecks(Map<String, Object> result) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
        return checks.stream()
                .filter(check -> !Boolean.TRUE.equals(check.get("passed")))
                .map(check -> String.valueOf(check.get("id")))
                .toList();
    }

    private Map<String, Object> scenario(
            int printerCount,
            double successRatePercent,
            long createMaxMs,
            long createP95Ms,
            long statusP95Ms,
            double completedThroughput,
            int acceptedRequests,
            int completed,
            int failed,
            int cancelled,
            int timedOut,
            int uniqueAccepted
    ) {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("printerCount", printerCount);
        scenario.put("requestSuccessRatePercent", successRatePercent);
        scenario.put("completedThroughputJobsPerSec", completedThroughput);
        scenario.put("acceptedRequests", acceptedRequests);
        scenario.put("uniqueAcceptedJobIds", uniqueAccepted);
        scenario.put("createLatencyMs", Map.of("max", createMaxMs, "p95", createP95Ms));
        scenario.put("statusLatencyMs", Map.of("p95", statusP95Ms));
        scenario.put("jobOutcomes", Map.of(
                "completed", completed,
                "failed", failed,
                "cancelled", cancelled,
                "timedOut", timedOut
        ));
        return scenario;
    }
}
