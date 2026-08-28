package com.printflow.performanceclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "printflow.client")
public class PerformanceClientProperties {

    private String mode = "health-check";
    private Load load = new Load();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Load getLoad() {
        return load;
    }

    public void setLoad(Load load) {
        this.load = load;
    }

    public static class Load {
        private String profile = "steady";
        private List<Integer> printerScenarios = new ArrayList<>(List.of(1, 2, 4));
        private long completionTimeoutSeconds = 240;
        private long statusPollIntervalMs = 500;
        private long simulatorStabilizationMs = 1500;
        private String outputFile = "";
        private Map<String, LoadProfile> profiles = new LinkedHashMap<>();

        public Load() {
            profiles.put("steady", new LoadProfile(5, 50));
            profiles.put("burst", new LoadProfile(20, 120));
            profiles.put("stress", new LoadProfile(40, 240));
        }

        public String getProfile() {
            return profile;
        }

        public void setProfile(String profile) {
            this.profile = profile;
        }

        public List<Integer> getPrinterScenarios() {
            return printerScenarios;
        }

        public void setPrinterScenarios(List<Integer> printerScenarios) {
            this.printerScenarios = printerScenarios;
        }

        public long getCompletionTimeoutSeconds() {
            return completionTimeoutSeconds;
        }

        public void setCompletionTimeoutSeconds(long completionTimeoutSeconds) {
            this.completionTimeoutSeconds = completionTimeoutSeconds;
        }

        public long getStatusPollIntervalMs() {
            return statusPollIntervalMs;
        }

        public void setStatusPollIntervalMs(long statusPollIntervalMs) {
            this.statusPollIntervalMs = statusPollIntervalMs;
        }

        public long getSimulatorStabilizationMs() {
            return simulatorStabilizationMs;
        }

        public void setSimulatorStabilizationMs(long simulatorStabilizationMs) {
            this.simulatorStabilizationMs = simulatorStabilizationMs;
        }

        public String getOutputFile() {
            return outputFile;
        }

        public void setOutputFile(String outputFile) {
            this.outputFile = outputFile;
        }

        public Map<String, LoadProfile> getProfiles() {
            return profiles;
        }

        public void setProfiles(Map<String, LoadProfile> profiles) {
            this.profiles = profiles;
        }
    }

    public static class LoadProfile {
        private int requestsPerSecond;
        private int totalRequests;

        public LoadProfile() {
        }

        public LoadProfile(int requestsPerSecond, int totalRequests) {
            this.requestsPerSecond = requestsPerSecond;
            this.totalRequests = totalRequests;
        }

        public int getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public void setRequestsPerSecond(int requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public int getTotalRequests() {
            return totalRequests;
        }

        public void setTotalRequests(int totalRequests) {
            this.totalRequests = totalRequests;
        }
    }
}
