package com.printflow.sharedmodel.protocol;

import com.printflow.sharedmodel.model.PrintJob;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PrintJobMessage extends SocketMessage {
    private String fileReference;
    private String profileId;
    private String profileName;
    private String paperSize;
    private String colorMode;
    private boolean duplexSupported;
    private Integer priority;
    private String userId;

    public static PrintJobMessage fromJob(PrintJob job) {
        PrintJobMessage message = new PrintJobMessage();
        message.setType("PRINT_JOB");
        message.setJobId(job.getId());
        message.setPrinterId(job.getAssignedPrinterId());
        message.setFileReference(job.getFileReference());
        if (job.getProfile() != null) {
            message.setProfileId(job.getProfile().getId());
            message.setProfileName(job.getProfile().getName());
            message.setPaperSize(job.getProfile().getPaperSize());
            message.setColorMode(job.getProfile().getColorMode());
            message.setDuplexSupported(job.getProfile().isDuplexSupported());
        }
        message.setPriority(job.getPriority());
        message.setUserId(job.getUserId());
        return message;
    }
}
