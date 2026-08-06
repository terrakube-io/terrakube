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
}
