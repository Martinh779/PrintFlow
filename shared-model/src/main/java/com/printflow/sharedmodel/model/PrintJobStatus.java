package com.printflow.sharedmodel.model;

public enum PrintJobStatus {
    CREATED,
    QUEUED,
    ASSIGNED,
    PRINTING,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    public boolean canTransitionTo(PrintJobStatus nextStatus) {
        if (nextStatus == null) {
            return false;
        }

        return switch (this) {
            case CREATED -> nextStatus == QUEUED || nextStatus == CANCELLED;
            case QUEUED -> nextStatus == ASSIGNED || nextStatus == CANCELLED;
            case ASSIGNED -> nextStatus == QUEUED || nextStatus == PRINTING || nextStatus == CANCELLED || nextStatus == FAILED;
            case PRINTING -> nextStatus == QUEUED || nextStatus == COMPLETED || nextStatus == FAILED;
            case COMPLETED, CANCELLED, FAILED -> false;
        };
    }
}