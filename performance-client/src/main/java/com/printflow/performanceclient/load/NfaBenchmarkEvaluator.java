package com.printflow.performanceclient.load;

import java.util.*;

public final class NfaBenchmarkEvaluator {

    public static final double REST_SUCCESS_RATE_MIN_PERCENT = 99.9;
    public static final long LOW_LOAD_MAX_LATENCY_MS = 200;
    public static final long STRESS_P95_MAX_LATENCY_MS = 500;
    public static final long STATUS_UPDATE_MAX_LATENCY_MS = 2_000;
    public static final double TWO_PRINTER_THROUGHPUT_IMPROVEMENT_MIN_PERCENT = 60.0;

    private NfaBenchmarkEvaluator() {
    }

    public static Map<String, Object> evaluate(int requestsPerSecond, List<Map<String, Object>> scenarios) {
        List<Map<String, Object>> safeScenarios = scenarios == null ? List.of() : scenarios;

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(checkRestSuccessRate(safeScenarios));
        checks.add(checkLowLoadLatency(requestsPerSecond, safeScenarios));
        checks.add(checkStressP95(requestsPerSecond, safeScenarios));
        checks.add(checkNoDuplicateOrLostAssignments(safeScenarios));
        checks.add(checkStatusUpdateLatency(safeScenarios));
        checks.add(checkThroughputScaleOneToTwoPrinters(safeScenarios));

        boolean overallPass = checks.stream()
                .allMatch(check -> Boolean.TRUE.equals(check.get("passed")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overallPass", overallPass);
        result.put("checks", checks);
        return result;
    }

    private static Map<String, Object> checkRestSuccessRate(List<Map<String, Object>> scenarios) {
        double minObserved = scenarios.stream()
                .mapToDouble(s -> asDouble(s.get("requestSuccessRatePercent")))
                .min()
                .orElse(0.0);
        boolean passed = !scenarios.isEmpty() && minObserved >= REST_SUCCESS_RATE_MIN_PERCENT;
        return check(
                "NFA-01",
                "99.9% successful REST requests",
                passed,
                Map.of(
                        "minimumObservedSuccessRatePercent", round2(minObserved),
                        "requiredSuccessRatePercent", REST_SUCCESS_RATE_MIN_PERCENT
                )
        );
    }

    private static Map<String, Object> checkLowLoadLatency(int requestsPerSecond, List<Map<String, Object>> scenarios) {
        if (requestsPerSecond >= 50) {
            return check(
                    "NFA-02",
                    "< 0.2s at <50 req/s",
                    false,
                    Map.of(
                            "reason", "Profile request rate is not below 50 req/s",
                            "requestsPerSecond", requestsPerSecond
                    )
            );
        }

        long maxLatency = scenarios.stream()
                .map(s -> asNestedLong(s, "createLatencyMs", "max"))
                .max(Long::compareTo)
                .orElse(Long.MAX_VALUE);

        boolean passed = !scenarios.isEmpty() && maxLatency <= LOW_LOAD_MAX_LATENCY_MS;
        return check(
                "NFA-02",
                "< 0.2s at <50 req/s",
                passed,
                Map.of(
                        "requestsPerSecond", requestsPerSecond,
                        "maxObservedLatencyMs", maxLatency,
                        "requiredMaxLatencyMs", LOW_LOAD_MAX_LATENCY_MS
                )
        );
    }

    private static Map<String, Object> checkStressP95(int requestsPerSecond, List<Map<String, Object>> scenarios) {
        if (requestsPerSecond != 40) {
            return check(
                    "NFA-03",
                    "p95 <= 500 ms at 40 req/s + 4 printers",
                    false,
                    Map.of(
                            "reason", "Profile request rate is not 40 req/s",
                            "requestsPerSecond", requestsPerSecond
                    )
            );
        }

        Optional<Map<String, Object>> scenario4Printers = scenarios.stream()
                .filter(s -> asInt(s.get("printerCount")) == 4)
                .findFirst();

        if (scenario4Printers.isEmpty()) {
            return check(
                    "NFA-03",
                    "p95 <= 500 ms at 40 req/s + 4 printers",
                    false,
                    Map.of("reason", "No scenario with 4 printers available")
            );
        }

        long p95 = asNestedLong(scenario4Printers.get(), "createLatencyMs", "p95");
        boolean passed = p95 <= STRESS_P95_MAX_LATENCY_MS;
        return check(
                "NFA-03",
                "p95 <= 500 ms at 40 req/s + 4 printers",
                passed,
                Map.of(
                        "requestsPerSecond", requestsPerSecond,
                        "printerCount", 4,
                        "observedP95Ms", p95,
                        "requiredP95Ms", STRESS_P95_MAX_LATENCY_MS
                )
        );
    }

    private static Map<String, Object> checkNoDuplicateOrLostAssignments(List<Map<String, Object>> scenarios) {
        boolean passed = !scenarios.isEmpty();
        List<Map<String, Object>> details = new ArrayList<>();

        for (Map<String, Object> scenario : scenarios) {
            int accepted = asInt(scenario.get("acceptedRequests"));
            int uniqueAccepted = asInt(scenario.get("uniqueAcceptedJobIds"));
            long accountedOutcomes = sumJobOutcomes(scenario);
            boolean scenarioPass = accepted == uniqueAccepted && accepted == accountedOutcomes;
            passed = passed && scenarioPass;

            details.add(Map.of(
                    "printerCount", asInt(scenario.get("printerCount")),
                    "acceptedRequests", accepted,
                    "uniqueAcceptedJobIds", uniqueAccepted,
                    "accountedOutcomes", accountedOutcomes,
                    "passed", scenarioPass
            ));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule", "acceptedRequests == uniqueAcceptedJobIds == accountedOutcomes");
        payload.put("scenarios", details);

        return check(
                "NFA-04",
                "No duplicate or lost job assignments",
                passed,
                payload
        );
    }

    private static Map<String, Object> checkStatusUpdateLatency(List<Map<String, Object>> scenarios) {
        long maxP95 = scenarios.stream()
                .map(s -> asNestedLong(s, "statusLatencyMs", "p95"))
                .max(Long::compareTo)
                .orElse(Long.MAX_VALUE);

        boolean passed = !scenarios.isEmpty() && maxP95 <= STATUS_UPDATE_MAX_LATENCY_MS;
        return check(
                "NFA-05",
                "Status updates within 2 seconds",
                passed,
                Map.of(
                        "maxObservedStatusP95Ms", maxP95,
                        "requiredMaxStatusP95Ms", STATUS_UPDATE_MAX_LATENCY_MS
                )
        );
    }

    private static Map<String, Object> checkThroughputScaleOneToTwoPrinters(List<Map<String, Object>> scenarios) {
        Optional<Map<String, Object>> onePrinter = scenarios.stream()
                .filter(s -> asInt(s.get("printerCount")) == 1)
                .findFirst();
        Optional<Map<String, Object>> twoPrinters = scenarios.stream()
                .filter(s -> asInt(s.get("printerCount")) == 2)
                .findFirst();

        if (onePrinter.isEmpty() || twoPrinters.isEmpty()) {
            return check(
                    "NFA-06",
                    "Throughput increase with 2 printers vs 1",
                    false,
                    Map.of("reason", "Both printer scenarios (1 and 2) are required")
            );
        }

        double throughputOne = asDouble(onePrinter.get().get("completedThroughputJobsPerSec"));
        double throughputTwo = asDouble(twoPrinters.get().get("completedThroughputJobsPerSec"));
        if (throughputOne <= 0) {
            return check(
                    "NFA-06",
                    "Throughput increase with 2 printers vs 1",
                    false,
                    Map.of("reason", "1-printer throughput must be > 0", "throughputOnePrinter", throughputOne)
            );
        }

        double improvementPercent = ((throughputTwo - throughputOne) / throughputOne) * 100.0;
        boolean passed = improvementPercent >= TWO_PRINTER_THROUGHPUT_IMPROVEMENT_MIN_PERCENT;
        return check(
                "NFA-06",
                "Throughput increase with 2 printers vs 1",
                passed,
                Map.of(
                        "throughputOnePrinterJobsPerSec", round2(throughputOne),
                        "throughputTwoPrintersJobsPerSec", round2(throughputTwo),
                        "improvementPercent", round2(improvementPercent),
                        "requiredImprovementPercent", TWO_PRINTER_THROUGHPUT_IMPROVEMENT_MIN_PERCENT
                )
        );
    }

    private static long sumJobOutcomes(Map<String, Object> scenario) {
        Map<?, ?> outcomes = asMap(scenario.get("jobOutcomes"));
        long completed = asLong(outcomes.get("completed"));
        long failed = asLong(outcomes.get("failed"));
        long cancelled = asLong(outcomes.get("cancelled"));
        long timedOut = asLong(outcomes.get("timedOut"));
        return completed + failed + cancelled + timedOut;
    }

    private static Map<String, Object> check(String id, String requirement, boolean passed, Map<String, Object> details) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("id", id);
        check.put("requirement", requirement);
        check.put("passed", passed);
        check.put("details", details);
        return check;
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private static long asNestedLong(Map<String, Object> map, String key, String nestedKey) {
        Map<?, ?> nested = asMap(map.get(key));
        return asLong(nested.get(nestedKey));
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s);
        }
        return 0;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s);
        }
        return 0L;
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Double.parseDouble(s);
        }
        return 0.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
