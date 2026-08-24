package com.printflow.sharedmodel.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintResult {
    private String printerId;
    private Duration duration;
    private Instant completedAt;
    private boolean successful;
    private String message;
}