package com.idp.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EventTest {

    @Test
    void testEventCreationAndSchemaValidation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();

        EventEnvelope event = new EventEnvelope(
            UUID.randomUUID(),
            "DocumentExtracted",
            1,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            mapper.createObjectNode().put("status", "SUCCESS")
        );

        assertNotNull(event);

        String schemaJson = """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "properties": {
            "eventId": { "type": "string", "format": "uuid" },
            "eventType": { "type": "string" },
            "schemaVersion": { "type": "integer" },
            "occurredAt": { "type": "number" },
            "tenantId": { "type": "string", "format": "uuid" },
            "correlationId": { "type": "string", "format": "uuid" },
            "payload": { "type": "object" }
          },
          "required": ["eventId", "eventType", "schemaVersion", "occurredAt", "tenantId", "payload"]
        }
        """;

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema schema = factory.getSchema(schemaJson);

        JsonNode eventNode = mapper.valueToTree(event);
        Set<ValidationMessage> errors = schema.validate(eventNode);

        assertTrue(errors.isEmpty(), "Schema validation failed: " + errors);
    }
}
