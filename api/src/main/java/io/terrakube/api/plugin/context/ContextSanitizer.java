package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@AllArgsConstructor
public class ContextSanitizer {

    private static final String CONTEXT_PLAN_KEY = "planStructuredOutput";
    private static final String CONTEXT_APPLY_KEY = "applyStructuredOutput";
    private static final String CONTEXT_OUTPUTS_KEY = "terraformOutputs";

    private final ObjectMapper objectMapper;

    public String sanitize(String context) throws JacksonException, IOException {
        JsonNode rootNode = objectMapper.readTree(context);
        if (rootNode instanceof ObjectNode rootObject) {
            sanitizeStructuredChanges(rootObject, CONTEXT_PLAN_KEY);
            sanitizeStructuredChanges(rootObject, CONTEXT_APPLY_KEY);
            sanitizeTerraformOutputs(rootObject);
        }

        return objectMapper.writeValueAsString(rootNode);
    }

    private void sanitizeTerraformOutputs(ObjectNode rootNode) {
        JsonNode outputsNode = rootNode.get(CONTEXT_OUTPUTS_KEY);
        if (!(outputsNode instanceof ObjectNode outputsObject)) {
            return;
        }

        outputsObject.fields().forEachRemaining(entry -> {
            if (!(entry.getValue() instanceof ArrayNode stepOutputs)) {
                return;
            }

            stepOutputs.forEach(outputNode -> {
                if (!(outputNode instanceof ObjectNode outputObject)) {
                    return;
                }

                JsonNode sensitiveNode = outputObject.get("sensitive");
                if (sensitiveNode != null && sensitiveNode.isBoolean() && sensitiveNode.booleanValue()) {
                    outputObject.set("value", NullNode.getInstance());
                }
            });
        });
    }

    private void sanitizeStructuredChanges(ObjectNode rootNode, String contextKey) {
        JsonNode structuredOutputNode = rootNode.get(contextKey);
        if (!(structuredOutputNode instanceof ObjectNode structuredOutputObject)) {
            return;
        }

        structuredOutputObject.fields().forEachRemaining(entry -> {
            if (!(entry.getValue() instanceof ArrayNode stepChanges)) {
                return;
            }

            stepChanges.forEach(changeNode -> {
                if (!(changeNode instanceof ObjectNode changeObject)) {
                    return;
                }

                sanitizeChangeValue(changeObject, "before", "beforeSensitive");
                sanitizeChangeValue(changeObject, "after", "afterSensitive");
            });
        });
    }

    private void sanitizeChangeValue(ObjectNode changeObject, String valueField, String sensitiveField) {
        JsonNode valueNode = changeObject.get(valueField);
        if (valueNode == null) {
            return;
        }

        JsonNode sensitiveNode = changeObject.get(sensitiveField);
        changeObject.set(valueField, sanitizeNode(valueNode, sensitiveNode));
    }

    private JsonNode sanitizeNode(JsonNode valueNode, JsonNode sensitiveNode) {
        if (sensitiveNode != null && sensitiveNode.isBoolean() && sensitiveNode.booleanValue()) {
            return NullNode.getInstance();
        }

        if (valueNode.isObject()) {
            ObjectNode sanitizedObject = objectMapper.createObjectNode();
            valueNode.fields().forEachRemaining(entry -> {
                JsonNode nestedSensitiveNode = sensitiveNode != null ? sensitiveNode.get(entry.getKey()) : null;
                sanitizedObject.set(entry.getKey(), sanitizeNode(entry.getValue(), nestedSensitiveNode));
            });
            return sanitizedObject;
        }

        if (valueNode.isArray()) {
            ArrayNode sanitizedArray = objectMapper.createArrayNode();
            ArrayNode sensitiveArray = sensitiveNode instanceof ArrayNode ? (ArrayNode) sensitiveNode : null;

            for (int index = 0; index < valueNode.size(); index++) {
                JsonNode nestedSensitiveNode = sensitiveArray != null && index < sensitiveArray.size()
                        ? sensitiveArray.get(index)
                        : null;
                sanitizedArray.add(sanitizeNode(valueNode.get(index), nestedSensitiveNode));
            }

            return sanitizedArray;
        }

        return valueNode.deepCopy();
    }
}
