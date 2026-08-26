package com.printflow.sharedmodel.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SocketMessage {
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private String type;
    private String printerId;
    private String jobId;
    private String status;
    private String message;
    private Map<String, Object> data = new LinkedHashMap<>();

    public SocketMessage(String type) {
        this.type = type;
    }

    public String toJson() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

    public static SocketMessage fromJson(String json) throws IOException {
        return OBJECT_MAPPER.readValue(json, SocketMessage.class);
    }

    public static <T extends SocketMessage> T fromJson(String json, Class<T> type) throws IOException {
        return OBJECT_MAPPER.readValue(json, type);
    }
}
