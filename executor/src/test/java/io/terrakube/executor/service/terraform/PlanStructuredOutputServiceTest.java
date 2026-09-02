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
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        return new PlanStructuredOutputService(jobContextService, new ObjectMapper(), new TerraformClient());
    }

    private TerraformProcessData captureShowPlanJsonData(boolean tofu) throws Exception {
        TerraformClient terraformClient = Mockito.mock(TerraformClient.class);
        Mockito.when(terraformClient.showPlanJson(Mockito.any(), Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                Mockito.mock(JobContextService.class),
                new ObjectMapper(),
                terraformClient);

        TerraformJob job = new TerraformJob();
        job.setJobId("1");
        job.setStepId("step-1");
        job.setTerraformVersion("1.11.5");
        job.setTofu(tofu);
        job.setEnvironmentVariables(new HashMap<>());

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

    // Regression test: getPlanAsJson runs a separate `show -json <planfile>` invocation after
    // the real plan already finished. That plan process gets its credentials (S3-backend auth,
    // dynamic cloud credentials, etc.) via TerraformJob.environmentVariables, loaded earlier by
    // TerraformExecutorServiceImpl.loadTempEnvironmentVariables - but getPlanAsJson built its own
    // bare TerraformProcessData without ever forwarding that map, so the separate `show` call ran
    // with none of them and failed against any backend/provider needing auth (surfaced to users
    // as "Error: No valid credential sources found"), even though the main plan had just
    // succeeded moments earlier using the same working directory.
    @Test
    void forwardsJobEnvironmentVariablesToTheShowCommand() throws Exception {
        TerraformClient terraformClient = Mockito.mock(TerraformClient.class);
        Mockito.when(terraformClient.showPlanJson(Mockito.any(), Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                Mockito.mock(JobContextService.class),
                new ObjectMapper(),
                terraformClient);

        TerraformJob job = new TerraformJob();
        job.setJobId("1");
        job.setStepId("step-1");
        job.setTerraformVersion("1.11.5");
        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("AWS_ACCESS_KEY_ID", "backend-key");
        job.setEnvironmentVariables(environmentVariables);

        service.getPlanAsJson(job, new File("/tmp"));

        ArgumentCaptor<TerraformProcessData> captor = ArgumentCaptor.forClass(TerraformProcessData.class);
        Mockito.verify(terraformClient).showPlanJson(captor.capture(), Mockito.any(), Mockito.any());
        assertEquals(environmentVariables, captor.getValue().getTerraformEnvironmentVariables());
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
                List.of(Map.of("action", "replace")),
                List.of());

        assertEquals("value", updatedContext.get("custom"));

        Map<String, Object> planStructuredOutput = (Map<String, Object>) updatedContext.get("planStructuredOutput");
        assertTrue(planStructuredOutput.containsKey("existing-step"));
        assertTrue(planStructuredOutput.containsKey("new-step"));

        Map<String, Object> terrakubeUi = (Map<String, Object>) updatedContext.get("terrakubeUI");
        assertTrue(terrakubeUi.containsKey("existing-step"));
        assertTrue(terrakubeUi.containsKey("new-step"));
    }

    @Test
    void mergeShowJsonDiffAddsDiffFieldsToLiveStreamedEntryByAddress() throws Exception {
        List<Map<String, Object>> liveChanges = new java.util.ArrayList<>();
        Map<String, Object> liveEntry = new HashMap<>();
        liveEntry.put("address", "aws_instance.foo");
        liveEntry.put("action", "create");
        liveEntry.put("status", "planned");
        liveChanges.add(liveEntry);

        String planJson = "{\"resource_changes\":[{\"address\":\"aws_instance.foo\",\"module_address\":null,\"type\":\"aws_instance\",\"name\":\"foo\",\"change\":{\"actions\":[\"create\"],\"before\":null,\"after\":{\"ami\":\"ami-1\"},\"after_unknown\":{},\"before_sensitive\":false,\"after_sensitive\":{}}}]}";

        List<Map<String, Object>> merged = subject().mergeShowJsonDiff(liveChanges, planJson);

        assertEquals(1, merged.size());
        assertEquals("planned", merged.get(0).get("status"));
        assertEquals("create", merged.get(0).get("action"));
        assertEquals(Map.of("ami", "ami-1"), merged.get(0).get("after"));
        assertEquals("aws_instance", merged.get(0).get("resourceType"));
    }

    // getPlanAsHumanText exists specifically to restore the classic multi-line diff that -json
    // mode never puts in the live event stream (only terse one-line "planned_change" summaries) -
    // it must call showPlan (the non-JSON renderer), not showPlanJson (already used for the
    // structured panel's data).
    @Test
    void getPlanAsHumanTextRendersViaShowPlanNotShowPlanJson() throws Exception {
        TerraformClient terraformClient = Mockito.mock(TerraformClient.class);
        Mockito.when(terraformClient.showPlan(Mockito.any(), Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenAnswer(invocation -> {
                    // The real client invokes its Consumer<String> once per line, with no
                    // embedded newline in each call (same as plan()/apply()'s JSON line
                    // consumers) - simulate that here rather than handing the whole diff to a
                    // single accept() call, which would hide a missing-line-separator bug.
                    Consumer<String> out = invocation.getArgument(1);
                    out.accept("  # aws_instance.foo will be created");
                    out.accept("  + resource \"aws_instance\" \"foo\" {");
                    out.accept("      + ami = \"ami-1\"");
                    out.accept("    }");
                    return CompletableFuture.completedFuture(true);
                });

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                Mockito.mock(JobContextService.class),
                new ObjectMapper(),
                terraformClient);

        TerraformJob job = new TerraformJob();
        job.setJobId("1");
        job.setStepId("step-1");
        job.setTerraformVersion("1.11.5");
        job.setEnvironmentVariables(new HashMap<>());

        String humanText = service.getPlanAsHumanText(job, new File("/tmp"));

        assertTrue(humanText.contains("will be created"));
        assertTrue(humanText.contains("+ ami = \"ami-1\""));
        // The regression this guards against: append() with no separator would collapse all
        // four accept() calls into one run-on line with no boundary between "created" and the
        // next line's "+ resource" - contains() alone wouldn't catch that, but a missing newline
        // between these two specific fragments would.
        assertTrue(humanText.contains("will be created\n  + resource"),
                "Expected each showPlan line to be newline-separated, not concatenated together: " + humanText);
        Mockito.verify(terraformClient).showPlan(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(terraformClient, Mockito.never()).showPlanJson(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void getPlanAsHumanTextReturnsNullWhenShowFails() throws Exception {
        TerraformClient terraformClient = Mockito.mock(TerraformClient.class);
        Mockito.when(terraformClient.showPlan(Mockito.any(), Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(false));

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                Mockito.mock(JobContextService.class),
                new ObjectMapper(),
                terraformClient);

        TerraformJob job = new TerraformJob();
        job.setJobId("1");
        job.setStepId("step-1");
        job.setTerraformVersion("1.11.5");
        job.setEnvironmentVariables(new HashMap<>());

        assertNull(service.getPlanAsHumanText(job, new File("/tmp")));
    }

    @Test
    void publishPlanProgressEnqueuesInsteadOfBlockingWhenAsyncEnabled() {
        io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue queue =
                Mockito.mock(io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue.class);
        Mockito.when(queue.nextSequence()).thenReturn(1L);
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        io.terrakube.executor.configuration.ExecutorFlagsProperties flags =
                new io.terrakube.executor.configuration.ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(true);
        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), Mockito.mock(TerraformClient.class), queue, flags);

        service.publishPlanProgress("o", "1", "step-1", List.of(Map.of("address", "a")), List.of());

        Mockito.verify(queue).submit(Mockito.argThat(s ->
                s.key().stepId().equals("step-1") && !s.isFinalSnapshot()));
        Mockito.verify(jobContextService, Mockito.never()).getCurrentContext(Mockito.any(), Mockito.any());
        Mockito.verify(jobContextService, Mockito.never()).saveContext(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void publishFinalPlanSnapshotStaysSynchronousWhenAsyncDisabled() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.getCurrentContext("o", "1")).thenReturn(new HashMap<>());
        io.terrakube.executor.configuration.ExecutorFlagsProperties flags =
                new io.terrakube.executor.configuration.ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(false);
        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), Mockito.mock(TerraformClient.class), null, flags);

        service.publishFinalPlanSnapshot("o", "1", "step-1", List.of(Map.of("address", "a")), List.of());

        Mockito.verify(jobContextService).getCurrentContext("o", "1");
        Mockito.verify(jobContextService).saveContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.any());
    }
}
