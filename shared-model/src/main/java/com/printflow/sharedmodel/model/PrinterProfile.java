package com.printflow.sharedmodel.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PrinterProfile {
    private String id;
    private String name;
    private String paperSize;
    private String colorMode;
    @JsonSetter(nulls = Nulls.SKIP)
    private boolean duplexSupported;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
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
}
