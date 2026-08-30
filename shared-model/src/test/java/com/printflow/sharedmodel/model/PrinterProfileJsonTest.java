package com.printflow.sharedmodel.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printflow.sharedmodel.dto.CreatePrintJobRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PrinterProfileJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesNullDuplexSupportedAsFalse() throws Exception {
        String json = """
                {
                  "id": "profile-1",
                  "name": "Office",
                  "paperSize": "A4",
                  "colorMode": "COLOR",
                  "duplexSupported": null
                }
                """;

        PrinterProfile profile = objectMapper.readValue(json, PrinterProfile.class);

        assertFalse(profile.isDuplexSupported());
    }

    @Test
    void deserializesCreateRequestWithNullDuplexSupported() throws Exception {
        String requestJson = """
                {
                  "fileReference": "example.pdf",
                  "profile": {
                    "id": "profile-1",
                    "duplexSupported": null
                  },
                  "priority": 1,
                  "userId": "admin"
                }
                """;

        CreatePrintJobRequest request = objectMapper.readValue(requestJson, CreatePrintJobRequest.class);

        assertFalse(request.getProfile().isDuplexSupported());
    }
}
