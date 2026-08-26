package com.printflow.server.service;

public class JobEnqueuedEvent {
    private final String jobId;

    public JobEnqueuedEvent(String jobId) {
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }
}
