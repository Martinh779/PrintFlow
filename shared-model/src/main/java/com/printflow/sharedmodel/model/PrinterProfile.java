package com.printflow.sharedmodel.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrinterProfile {
    private String id;
    private String name;
    private String paperSize;
    private String colorMode;
    private boolean duplexSupported;
}