package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanStructuredOutputServiceTest {

    private PlanStructuredOutputService subject() {
        WorkspaceSecurity workspaceSecurity = Mockito.mock(WorkspaceSecurity.class);
        return new PlanStructuredOutputService(workspaceSecurity, new ObjectMapper(), "http://terrakube-api");
    }

    @Test
    void normalizesReplaceActionsAndPreservesSensitiveMetadata() throws Exception {
        String json = """
                {
                  "resource_changes": [
                    {
                      "address": "aws_instance.example",
                      "type": "aws_instance",
                      "name": "example",
                      "change": {
                        "actions": ["delete", "create"],
                        "before": {"name": "old"},
                        "before_sensitive": {"password": true},
                        "after": {"name": "new"},
                        "after_sensitive": {"password": true},
                        "after_unknown": {"id": true}
                      }
                    }
                  ]
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        assertEquals(1, changes.size());
        assertEquals("replace", changes.get(0).get("action"));
        assertEquals(List.of("delete", "create"), changes.get(0).get("actions"));
        assertEquals(Map.of("password", true), changes.get(0).get("beforeSensitive"));
        assertEquals(Map.of("password", true), changes.get(0).get("afterSensitive"));
    }

    @Test
    void redactsSensitiveValuesFromStructuredPlanPayload() throws Exception {
        String json = """
                {
                  "resource_changes": [
                    {
                      "address": "railway_variable_collection.img",
                      "type": "railway_variable_collection",
                      "name": "img",
                      "change": {
                        "actions": ["update"],
                        "before": {
                          "variables": [
                            {
                              "name": "CONSUMER_COUNT",
                              "value": "0"
                            }
                          ]
                        },
                        "before_sensitive": {
                          "variables": [
                            {
                              "value": true
                            }
                          ]
                        },
                        "after": {
                          "variables": [
                            {
                              "name": "CONSUMER_COUNT",
                              "value": "2"
                            }
                          ]
                        },
                        "after_sensitive": {
                          "variables": [
                            {
                              "value": true
                            }
                          ]
                        },
                        "after_unknown": {}
                      }
                    }
                  ]
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        Map<String, Object> before = (Map<String, Object>) changes.get(0).get("before");
        Map<String, Object> after = (Map<String, Object>) changes.get(0).get("after");
        List<Map<String, Object>> beforeVariables = (List<Map<String, Object>>) before.get("variables");
        List<Map<String, Object>> afterVariables = (List<Map<String, Object>>) after.get("variables");

        assertEquals("CONSUMER_COUNT", beforeVariables.get(0).get("name"));
        assertNull(beforeVariables.get(0).get("value"));
        assertEquals("CONSUMER_COUNT", afterVariables.get(0).get("name"));
        assertNull(afterVariables.get(0).get("value"));
        assertEquals(
                Map.of("variables", List.of(Map.of("value", true))),
                changes.get(0).get("changedSensitive"));
    }

    @Test
    void ignoresUnchangedSensitiveValuesWhenBuildingStructuredPlanPayload() throws Exception {
        String json = """
                {
                  "resource_changes": [
                    {
                      "address": "aws_secretsmanager_secret_version.example",
                      "type": "aws_secretsmanager_secret_version",
                      "name": "example",
                      "change": {
                        "actions": ["update"],
                        "before": {
                          "secret_string": "same"
                        },
                        "before_sensitive": {
                          "secret_string": true
                        },
                        "after": {
                          "secret_string": "same"
                        },
                        "after_sensitive": {
                          "secret_string": true
                        },
                        "after_unknown": {}
                      }
                    }
                  ]
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        Map<String, Object> before = (Map<String, Object>) changes.get(0).get("before");
        Map<String, Object> after = (Map<String, Object>) changes.get(0).get("after");

        assertNull(before.get("secret_string"));
        assertNull(after.get("secret_string"));
        assertNull(changes.get(0).get("changedSensitive"));
    }

    @Test
    void skipsNoOpResourceChanges() throws Exception {
        String json = """
                {
                  "resource_changes": [
                    {
                      "address": "aws_instance.example",
                      "change": {
                        "actions": ["no-op"]
                      }
                    }
                  ]
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        assertTrue(changes.isEmpty());
    }

    @Test
    void mergesStructuredPlanDataWithoutDroppingExistingContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("custom", "value");
        context.put("planStructuredOutput", Map.of("existing-step", List.of(Map.of("action", "create"))));
        context.put("terrakubeUI", Map.of("existing-step", "<div>existing</div>"));

        Map<String, Object> updatedContext = subject().updateContext(
                context,
                "new-step",
                List.of(Map.of("action", "replace")));

        assertEquals("value", updatedContext.get("custom"));

        Map<String, Object> planStructuredOutput = (Map<String, Object>) updatedContext.get("planStructuredOutput");
        assertTrue(planStructuredOutput.containsKey("existing-step"));
        assertTrue(planStructuredOutput.containsKey("new-step"));

        Map<String, Object> terrakubeUi = (Map<String, Object>) updatedContext.get("terrakubeUI");
        assertTrue(terrakubeUi.containsKey("existing-step"));
        assertTrue(terrakubeUi.containsKey("new-step"));
    }
}
