package com.printflow.sharedmodel.dto;

import com.printflow.sharedmodel.model.PrinterProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePrintJobRequest {
    @NotBlank(message = "fileReference must not be blank")
    private String fileReference;

    @NotNull(message = "profile must not be null")
    private PrinterProfile profile;

    @NotNull(message = "priority must not be null")
    @Positive(message = "priority must be positive")
    private Integer priority;

    private String userId;
}
