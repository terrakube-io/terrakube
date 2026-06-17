package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import io.terrakube.terraform.TerraformClient;
import io.terrakube.terraform.TerraformProcessData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanStructuredOutputServiceTest {

    private PlanStructuredOutputService subject() {
        WorkspaceSecurity workspaceSecurity = Mockito.mock(WorkspaceSecurity.class);
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        return new PlanStructuredOutputService(workspaceSecurity, new ObjectMapper(), "http://terrakube-api", new TerraformClient(), terrakubeClient);
    }

    private TerraformProcessData captureShowPlanJsonData(boolean tofu) throws Exception {
        TerraformClient terraformClient = Mockito.mock(TerraformClient.class);
        Mockito.when(terraformClient.showPlanJson(Mockito.any(), Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                Mockito.mock(WorkspaceSecurity.class),
                new ObjectMapper(),
                "http://terrakube-api",
                terraformClient,
                Mockito.mock(TerrakubeClient.class));

        TerraformJob job = new TerraformJob();
        job.setJobId("1");
        job.setStepId("step-1");
        job.setTerraformVersion("1.11.5");
        job.setTofu(tofu);

        service.getPlanAsJson(job, new File("/tmp"));

        ArgumentCaptor<TerraformProcessData> captor = ArgumentCaptor.forClass(TerraformProcessData.class);
        Mockito.verify(terraformClient).showPlanJson(captor.capture(), Mockito.any(), Mockito.any());
        return captor.getValue();
    }

    @Test
    void readsPlanJsonWithTofuBinaryForOpenTofuWorkspaces() throws Exception {
        assertTrue(captureShowPlanJsonData(true).isTofu(),
                "Structured plan output must read the plan with the tofu binary for OpenTofu workspaces");
    }

    @Test
    void readsPlanJsonWithTerraformBinaryForTerraformWorkspaces() throws Exception {
        assertFalse(captureShowPlanJsonData(false).isTofu(),
                "Structured plan output must read the plan with the terraform binary for Terraform workspaces");
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
    void includesNoOpChangesWithImportingFieldAsImportAction() throws Exception {
        String json = """
                {
                  "resource_changes": [
                    {
                      "address": "aws_instance.example",
                      "type": "aws_instance",
                      "name": "example",
                      "change": {
                        "actions": ["no-op"],
                        "before": {"id": "i-abc123", "ami": "ami-12345"},
                        "after": {"id": "i-abc123", "ami": "ami-12345"},
                        "after_unknown": {},
                        "before_sensitive": {},
                        "after_sensitive": {},
                        "importing": {"id": "i-abc123"}
                      }
                    }
                  ]
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        assertEquals(1, changes.size());
        assertEquals("import", changes.get(0).get("action"));
        assertEquals("aws_instance.example", changes.get(0).get("address"));
        assertEquals(Map.of("id", "i-abc123"), changes.get(0).get("importing"));
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
