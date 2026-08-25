package com.printflow.sharedmodel.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class StatusUpdateMessage extends SocketMessage {
    private boolean successful;
    private long durationMs;
    private String detail;

    public StatusUpdateMessage(String printerId, String jobId, String status, boolean successful, long durationMs, String detail) {
        super("STATUS_UPDATE");
        setPrinterId(printerId);
        setJobId(jobId);
        setStatus(status);
        this.successful = successful;
        this.durationMs = durationMs;
        this.detail = detail;
    }

    public static StatusUpdateMessage printing(String printerId, String jobId) {
        return new StatusUpdateMessage(printerId, jobId, "DRUCKT", true, 250L, "Printing started");
    }

    public static StatusUpdateMessage completed(String printerId, String jobId, long durationMs) {
        return new StatusUpdateMessage(printerId, jobId, "ABGESCHLOSSEN", true, durationMs, "Completed successfully");
    }

    public static StatusUpdateMessage failed(String printerId, String jobId, String errorMessage) {
        return new StatusUpdateMessage(printerId, jobId, "FEHLGESCHLAGEN", false, 0L, errorMessage);
    }
}
