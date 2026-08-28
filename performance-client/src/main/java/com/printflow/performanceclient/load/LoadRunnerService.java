package com.printflow.performanceclient.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printflow.performanceclient.config.PerformanceClientProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Service
public class LoadRunnerService {

    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private final RestTemplate restTemplate;
    private final PerformanceClientProperties properties;
    private final ObjectMapper objectMapper;

    @Value("${printflow.server.base-url:http://localhost:8081}")
    private String serverBaseUrl;

    public LoadRunnerService(RestTemplate restTemplate, PerformanceClientProperties properties, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void execute(String[] args) throws Exception {
        verifyServerHealth();

        PerformanceClientProperties.Load loadConfig = properties.getLoad();
        String profileName = resolveProfileName(args, loadConfig.getProfile());
        PerformanceClientProperties.LoadProfile profile = resolveProfile(profileName, loadConfig.getProfiles());
        List<Integer> scenarios = resolvePrinterScenarios(args, loadConfig.getPrinterScenarios());

        List<Map<String, Object>> scenarioReports = new ArrayList<>();
        int maxScenario = scenarios.stream().max(Integer::compareTo).orElse(1);
        for (Integer printerCount : scenarios) {
            if (printerCount == null || printerCount <= 0) {
                throw new IllegalArgumentException("Printer scenarios must contain positive integers");
            }

            syncSimulators(printerCount, maxScenario);
            sleepMillis(loadConfig.getSimulatorStabilizationMs());
            scenarioReports.add(runScenario(profileName, profile, printerCount));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString());
        report.put("serverBaseUrl", serverBaseUrl);
        report.put("profile", profileName);
        report.put("profileConfig", Map.of(
                "requestsPerSecond", profile.getRequestsPerSecond(),
                "totalRequests", profile.getTotalRequests()
        ));
        report.put("scenarios", scenarioReports);
        report.put("nfaEvaluation", NfaBenchmarkEvaluator.evaluate(profile.getRequestsPerSecond(), scenarioReports));

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        System.out.println(json);

        String outputFile = resolveOutputFile(args, loadConfig.getOutputFile());
        if (!outputFile.isBlank()) {
            Path outputPath = Path.of(outputFile);
            Path absoluteOutputPath = outputPath.toAbsolutePath();
            Path parent = absoluteOutputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(absoluteOutputPath, json);
            System.out.println("Benchmark report written to: " + outputPath.toAbsolutePath());
        }
    }

    private Map<String, Object> runScenario(
            String profileName,
            PerformanceClientProperties.LoadProfile profile,
            int printerCount
    ) {
        int totalRequests = profile.getTotalRequests();
        int requestsPerSecond = profile.getRequestsPerSecond();
        if (totalRequests <= 0) {
            throw new IllegalArgumentException("totalRequests must be > 0 for profile " + profileName);
        }
        if (requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be > 0 for profile " + profileName);
        }

        SubmissionMetrics submission = submitJobs(totalRequests, requestsPerSecond, printerCount);
        CompletionMetrics completion = waitForTerminalStates(submission.createdJobIds);

        double submissionSeconds = Math.max(0.001, submission.elapsedMs / 1000.0);
        double totalRuntimeSeconds = Math.max(0.001, (submission.elapsedMs + completion.elapsedMs) / 1000.0);
        double requestSuccessRate = submission.successCount * 100.0 / totalRequests;
        double completedThroughput = completion.terminalCount / totalRuntimeSeconds;
        double acceptedThroughput = submission.successCount / submissionSeconds;

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("printerCount", printerCount);
        scenario.put("profile", profileName);
        scenario.put("requestsPerSecond", requestsPerSecond);
        scenario.put("submittedRequests", totalRequests);
        scenario.put("acceptedRequests", submission.successCount);
        scenario.put("uniqueAcceptedJobIds", submission.uniqueAcceptedJobIds);
        scenario.put("submissionErrors", submission.errorCount);
        scenario.put("requestSuccessRatePercent", round2(requestSuccessRate));
        scenario.put("submissionThroughputReqPerSec", round2(acceptedThroughput));
        scenario.put("completedThroughputJobsPerSec", round2(completedThroughput));
        scenario.put("submissionDurationMs", submission.elapsedMs);
        scenario.put("completionWaitDurationMs", completion.elapsedMs);
        scenario.put("createLatencyMs", toMap(LatencyCalculator.summarize(submission.latencySamplesMs)));
        scenario.put("statusLatencyMs", toMap(LatencyCalculator.summarize(completion.statusLatencySamplesMs)));
        scenario.put("jobOutcomes", Map.of(
                "completed", completion.completedCount,
                "failed", completion.failedCount,
                "cancelled", completion.cancelledCount,
                "timedOut", completion.timedOutCount
        ));
        scenario.put("statusPollErrors", completion.pollErrorCount);
        return scenario;
    }

    private SubmissionMetrics submitJobs(int totalRequests, int requestsPerSecond, int printerCount) {
        String createUrl = serverBaseUrl + "/api/jobs";
        long intervalNanos = 1_000_000_000L / requestsPerSecond;
        long scenarioStart = System.nanoTime();
        long nextDispatch = scenarioStart;

        List<Long> latenciesMs = new ArrayList<>();
        List<String> createdJobIds = new ArrayList<>();
        int successCount = 0;
        int errorCount = 0;

        for (int i = 0; i < totalRequests; i++) {
            long now = System.nanoTime();
            if (now < nextDispatch) {
                LockSupport.parkNanos(nextDispatch - now);
            }
            nextDispatch += intervalNanos;

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("fileReference", "load-" + printerCount + "-" + i + ".pdf");
            requestBody.put("priority", 1);
            requestBody.put("userId", "load-runner");
            requestBody.put("profile", defaultProfile());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            long start = System.nanoTime();
            try {
                ResponseEntity<Map> response = restTemplate.postForEntity(createUrl, request, Map.class);
                long latencyMs = elapsedMs(start);
                latenciesMs.add(latencyMs);

                if (!response.getStatusCode().is2xxSuccessful()) {
                    errorCount++;
                    continue;
                }

                Object body = response.getBody();
                if (!(body instanceof Map<?, ?> bodyMap)) {
                    errorCount++;
                    continue;
                }

                Object idValue = bodyMap.get("id");
                if (!(idValue instanceof String id) || id.isBlank()) {
                    errorCount++;
                    continue;
                }

                createdJobIds.add(id);
                successCount++;
            } catch (RestClientResponseException | ResourceAccessException ex) {
                latenciesMs.add(elapsedMs(start));
                errorCount++;
            }
        }

        long elapsedMs = elapsedMs(scenarioStart);
        int uniqueAcceptedJobIds = new HashSet<>(createdJobIds).size();
        return new SubmissionMetrics(successCount, uniqueAcceptedJobIds, errorCount, elapsedMs, latenciesMs, createdJobIds);
    }

    private CompletionMetrics waitForTerminalStates(List<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return CompletionMetrics.empty();
        }

        Set<String> pending = new HashSet<>(jobIds);
        List<Long> statusLatenciesMs = new ArrayList<>();
        int pollErrorCount = 0;
        int completed = 0;
        int failed = 0;
        int cancelled = 0;

        long start = System.nanoTime();
        long timeoutMs = properties.getLoad().getCompletionTimeoutSeconds() * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            List<String> snapshot = new ArrayList<>(pending);
            for (String id : snapshot) {
                String getUrl = serverBaseUrl + "/api/jobs/" + id;
                long requestStart = System.nanoTime();
                try {
                    Map response = restTemplate.getForObject(getUrl, Map.class);
                    statusLatenciesMs.add(elapsedMs(requestStart));
                    if (!(response instanceof Map<?, ?> bodyMap)) {
                        pollErrorCount++;
                        continue;
                    }

                    Object statusObj = bodyMap.get("status");
                    if (!(statusObj instanceof String status)) {
                        continue;
                    }
                    String normalized = status.toUpperCase(Locale.ROOT);
                    if (!TERMINAL_STATES.contains(normalized)) {
                        continue;
                    }

                    if ("COMPLETED".equals(normalized)) {
                        completed++;
                    } else if ("FAILED".equals(normalized)) {
                        failed++;
                    } else if ("CANCELLED".equals(normalized)) {
                        cancelled++;
                    }
                    pending.remove(id);
                } catch (RestClientResponseException | ResourceAccessException ex) {
                    statusLatenciesMs.add(elapsedMs(requestStart));
                    pollErrorCount++;
                }
            }

            if (!pending.isEmpty()) {
                sleepMillis(properties.getLoad().getStatusPollIntervalMs());
            }
        }

        long elapsedMs = elapsedMs(start);
        int timedOut = pending.size();
        return new CompletionMetrics(
                completed + failed + cancelled,
                completed,
                failed,
                cancelled,
                timedOut,
                pollErrorCount,
                elapsedMs,
                statusLatenciesMs
        );
    }

    private Map<String, Object> defaultProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", "default-profile");
        profile.put("name", "A4 Mono");
        profile.put("paperSize", "A4");
        profile.put("colorMode", "MONOCHROME");
        profile.put("duplexSupported", false);
        return profile;
    }

    private void syncSimulators(int targetCount, int maxScenarioCount) {
        for (int i = 1; i <= maxScenarioCount; i++) {
            String printerId = "perf-sim-" + i;
            if (i <= targetCount) {
                callSimulatorEndpoint(printerId, true);
            } else {
                callSimulatorEndpoint(printerId, false);
            }
        }
    }

    private void callSimulatorEndpoint(String printerId, boolean start) {
        String path = start ? "/start" : "/stop";
        String url = serverBaseUrl + "/api/admin/printers/" + printerId + "/simulator" + path;
        try {
            restTemplate.exchange(url, HttpMethod.POST, HttpEntity.EMPTY, String.class);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (start && code == 409) {
                return;
            }
            if (!start && code == 404) {
                return;
            }
            throw new IllegalStateException("Failed to " + (start ? "start" : "stop")
                    + " simulator " + printerId + " (HTTP " + code + ")", ex);
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException("Admin endpoint unavailable while managing simulators", ex);
        }
    }

    private void verifyServerHealth() {
        String healthUrl = serverBaseUrl + "/actuator/health";
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(healthUrl, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Server health endpoint returned " + response.getStatusCode());
            }
        } catch (RestClientResponseException | ResourceAccessException ex) {
            throw new IllegalStateException("Cannot reach server health endpoint: " + healthUrl, ex);
        }
    }

    private String resolveProfileName(String[] args, String configuredProfile) {
        for (String arg : args) {
            if (arg.startsWith("--profile=")) {
                return arg.substring("--profile=".length()).trim();
            }
        }
        return configuredProfile == null ? "" : configuredProfile.trim();
    }

    private String resolveOutputFile(String[] args, String configuredOutputFile) {
        for (String arg : args) {
            if (arg.startsWith("--output=")) {
                return arg.substring("--output=".length()).trim();
            }
        }
        return configuredOutputFile == null ? "" : configuredOutputFile.trim();
    }

    private List<Integer> resolvePrinterScenarios(String[] args, List<Integer> configured) {
        for (String arg : args) {
            if (arg.startsWith("--printers=")) {
                String raw = arg.substring("--printers=".length()).trim();
                if (raw.isBlank()) {
                    break;
                }
                String[] pieces = raw.split(",");
                List<Integer> parsed = new ArrayList<>();
                for (String piece : pieces) {
                    parsed.add(Integer.parseInt(piece.trim()));
                }
                return parsed;
            }
        }
        return configured == null ? List.of(1, 2, 4) : configured;
    }

    private PerformanceClientProperties.LoadProfile resolveProfile(
            String profileName,
            Map<String, PerformanceClientProperties.LoadProfile> profiles
    ) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("No load profile configured. Provide printflow.client.load.profile");
        }
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("No load profiles configured under printflow.client.load.profiles");
        }

        PerformanceClientProperties.LoadProfile profile = profiles.get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown load profile: " + profileName + ". Available: " + profiles.keySet());
        }
        return profile;
    }

    private Map<String, Object> toMap(LatencySummary summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", summary.minMs());
        m.put("max", summary.maxMs());
        m.put("avg", round2(summary.avgMs()));
        m.put("p50", summary.p50Ms());
        m.put("p95", summary.p95Ms());
        return m;
    }

    private long elapsedMs(long startNano) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);
    }

    private void sleepMillis(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", ex);
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record SubmissionMetrics(
            int successCount,
            int uniqueAcceptedJobIds,
            int errorCount,
            long elapsedMs,
            List<Long> latencySamplesMs,
            List<String> createdJobIds
    ) {
    }

    private record CompletionMetrics(
            int terminalCount,
            int completedCount,
            int failedCount,
            int cancelledCount,
            int timedOutCount,
            int pollErrorCount,
            long elapsedMs,
            List<Long> statusLatencySamplesMs
    ) {
        private static CompletionMetrics empty() {
            return new CompletionMetrics(0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }
}
