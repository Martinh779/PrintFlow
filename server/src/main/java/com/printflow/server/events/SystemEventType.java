package com.printflow.server.events;

public enum SystemEventType {
    JOB_CREATED("Job created"),
    JOB_QUEUED("Job queued"),
    JOB_ASSIGNED("Job assigned"),
    JOB_COMPLETED("Job completed"),
    JOB_CANCELLED("Job cancelled"),
    PRINTER_FAILED("Printer failed"),
    SOCKET_DISCONNECT("Socket disconnect"),
    RETRY_RECOVERY("Retry / recovery");

    private final String label;

    SystemEventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
