package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSanitizerTest {

    private ContextSanitizer subject() {
        return new ContextSanitizer(new ObjectMapper());
    }

    @Test
    void redactsSensitiveAfterValueInApplyStructuredOutput() throws Exception {
        String context = "{\"applyStructuredOutput\":{\"step-1\":[{\"address\":\"aws_instance.foo\",\"after\":{\"password\":\"secret\"},\"afterSensitive\":{\"password\":true}}]}}";

        String sanitized = subject().sanitize(context);

        assertTrue(sanitized.contains("\"password\":null"));
        assertEquals(-1, sanitized.indexOf("secret"));
    }

    @Test
    void redactsSensitiveOutputValue() throws Exception {
        String context = "{\"terraformOutputs\":{\"step-1\":[{\"name\":\"db_password\",\"value\":\"secret\",\"sensitive\":true}]}}";

        String sanitized = subject().sanitize(context);

        assertTrue(sanitized.contains("\"value\":null"));
    }

    @Test
    void preservesStructuredOutputStatusMetadata() throws Exception {
        String context = "{\"structuredOutputStatus\":{\"state\":\"PERSISTED\",\"updatedAtEpochMs\":123,\"phase\":\"apply\"}}";

        String sanitized = subject().sanitize(context);

        assertTrue(sanitized.contains("\"state\":\"PERSISTED\""));
        assertTrue(sanitized.contains("\"updatedAtEpochMs\":123"));
    }

    @Test
    void preservesNoChangePlanMarkerAndExplicitEmptyPlanArray() throws Exception {
        String context = "{\"noChangePlan\":{\"planStepId\":\"step-1\"},"
                + "\"planStructuredOutput\":{\"step-1\":[]}}";

        String sanitized = subject().sanitize(context);

        assertTrue(sanitized.contains("\"noChangePlan\":{\"planStepId\":\"step-1\"}"), sanitized);
        assertTrue(sanitized.contains("\"planStructuredOutput\":{\"step-1\":[]}"), sanitized);
    }
}
