package com.printflow.sharedmodel.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PrinterProfile {
    private String id;
    private String name;
    private String paperSize;
    private String colorMode;
    private boolean duplexSupported;

    @JsonCreator
    public PrinterProfile(@JsonProperty("id") String id,
                          @JsonProperty("name") String name,
                          @JsonProperty("paperSize") String paperSize,
                          @JsonProperty("colorMode") String colorMode,
                          @JsonProperty("duplexSupported") Boolean duplexSupported) {
        this.id = id;
        this.name = name;
        this.paperSize = paperSize;
        this.colorMode = colorMode;
        this.duplexSupported = Boolean.TRUE.equals(duplexSupported);
    }

    public PrinterProfile(String id,
                          String name,
                          String paperSize,
                          String colorMode,
                          boolean duplexSupported) {
        this(id, name, paperSize, colorMode, Boolean.valueOf(duplexSupported));
    }
}
