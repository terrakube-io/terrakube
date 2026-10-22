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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void surfacesOutputOnlyChangesWhenNoResourceChangesExist() throws Exception {
        String json = """
                {
                  "resource_changes": [],
                  "output_changes": {
                    "derived_value": {
                      "actions": ["update"],
                      "before": "old-value",
                      "after": "new-value",
                      "after_unknown": false,
                      "before_sensitive": false,
                      "after_sensitive": false
                    }
                  }
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        assertEquals(1, changes.size());
        assertEquals("derived_value", changes.get(0).get("address"));
        assertEquals("output", changes.get(0).get("resourceType"));
        assertEquals("update", changes.get(0).get("action"));
        assertEquals("old-value", changes.get(0).get("before"));
        assertEquals("new-value", changes.get(0).get("after"));
        assertEquals(true, changes.get(0).get("isOutputChange"));
    }

    @Test
    void redactsSensitiveOutputValuesTheSameWayAsResourceAttributes() throws Exception {
        String json = """
                {
                  "resource_changes": [],
                  "output_changes": {
                    "api_key": {
                      "actions": ["update"],
                      "before": "old-secret",
                      "after": "new-secret",
                      "after_unknown": false,
                      "before_sensitive": true,
                      "after_sensitive": true
                    }
                  }
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        assertEquals(1, changes.size());
        assertNull(changes.get(0).get("before"));
        assertNull(changes.get(0).get("after"));
        assertEquals(true, changes.get(0).get("beforeSensitive"));
        assertEquals(true, changes.get(0).get("afterSensitive"));
    }

    @Test
    void toleratesAnOutputChangeWithNullActions() throws Exception {
        String json = """
                {
                  "resource_changes": [],
                  "output_changes": {
                    "odd_output": {
                      "actions": null,
                      "before": "a",
                      "after": "b"
                    }
                  }
                }
                """;

        // Must not throw a NullPointerException; a null actions array is treated as empty.
        subject().buildChangesFromPlanJson(json);
    }

    @Test
    void skipsNoOpOutputChanges() throws Exception {
        String json = """
                {
                  "resource_changes": [],
                  "output_changes": {
                    "unchanged_output": {
                      "actions": ["no-op"],
                      "before": "same",
                      "after": "same",
                      "after_unknown": false,
                      "before_sensitive": false,
                      "after_sensitive": false
                    }
                  }
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        assertTrue(changes.isEmpty());
    }

    @Test
    void returnsEmptyChangesWhenNeitherResourceNorOutputChangesExist() throws Exception {
        String json = """
                {
                  "resource_changes": [],
                  "output_changes": {}
                }
                """;

        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);

        assertTrue(changes.isEmpty());

        Map<String, Object> set = subject().buildPlanPatch("step-1", changes, List.of(), true);
        assertEquals(Map.of("planStepId", "step-1"), set.get(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY));
    }

    @Test
    void doesNotSetNoChangePlanMarkerWhenErrorDiagnosticsPresent() {
        List<Map<String, Object>> changes = List.of();
        List<Map<String, Object>> jobDiagnostics = List.of(Map.of("severity", "error", "summary", "Plugin failure"));

        Map<String, Object> set = subject().buildPlanPatch("step-1", changes, jobDiagnostics, true);

        assertNull(set.get(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY),
                "noChangePlan marker must be suppressed when plan encountered error diagnostics");
    }

    @Test
    void doesNotSetNoChangePlanMarkerWhenErroredResourcesPresent() {
        List<Map<String, Object>> changes = List.of(Map.of("address", "aws_instance.failed", "status", "errored"));
        List<Map<String, Object>> jobDiagnostics = List.of();

        Map<String, Object> set = subject().buildPlanPatch("step-1", changes, jobDiagnostics, true);

        assertNull(set.get(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY),
                "noChangePlan marker must be suppressed when plan encountered errored resources");
    }

    @Test
    void setsNoChangePlanMarkerWhenChangesEmptyAndOnlyWarningDiagnosticsPresent() {
        List<Map<String, Object>> changes = List.of();
        List<Map<String, Object>> jobDiagnostics = List.of(Map.of("severity", "warning", "summary", "Deprecated syntax"));

        Map<String, Object> set = subject().buildPlanPatch("step-1", changes, jobDiagnostics, true);

        assertEquals(Map.of("planStepId", "step-1"), set.get(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY));
    }

    @Test
    void buildPlanPatchTouchesOnlyThisStepsEntries() {
        List<Map<String, Object>> changes = List.of(Map.of("action", "replace"));
        List<Map<String, Object>> diagnostics = List.of(Map.of("severity", "warning"));

        Map<String, Object> set = subject().buildPlanPatch("new-step", changes, diagnostics, false);

        assertEquals(3, set.size());
        assertEquals(changes, set.get("planStructuredOutput/new-step"));
        assertEquals("<div data-terrakube-structured-plan=\"true\"></div>", set.get("terrakubeUI/new-step"));
        assertEquals(diagnostics, set.get("jobDiagnostics/new-step"));
        // Progress writes never touch the no-change marker.
        assertFalse(set.containsKey(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY));
    }

    @Test
    void finalPlanPatchWithChangesClearsTheNoChangePlanMarker() {
        Map<String, Object> set = subject().buildPlanPatch("step-1", List.of(Map.of("action", "create")), List.of(), true);

        assertTrue(set.containsKey(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY));
        assertNull(set.get(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY));
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
        Mockito.when(queue.nextSequence("1")).thenReturn(1L);
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        io.terrakube.executor.configuration.ExecutorFlagsProperties flags =
                new io.terrakube.executor.configuration.ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(true);
        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), Mockito.mock(TerraformClient.class), queue, flags);

        service.publishPlanProgress("o", "1", "step-1", List.of(Map.of("address", "a")), List.of());

        Mockito.verify(queue).submit(Mockito.argThat(s ->
                s.key().stepId().equals("step-1") && !s.isFinalSnapshot()));
        Mockito.verify(jobContextService, Mockito.never()).patchContextFresh(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishFinalPlanSnapshotStaysSynchronousWhenAsyncDisabled() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        io.terrakube.executor.configuration.ExecutorFlagsProperties flags =
                new io.terrakube.executor.configuration.ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(false);
        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), Mockito.mock(TerraformClient.class), null, flags);

        service.publishFinalPlanSnapshot("o", "1", "step-1", List.of(Map.of("address", "a")), List.of());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(jobContextService).patchContextFresh(Mockito.eq("o"), Mockito.eq("1"), captor.capture());
        Mockito.verify(jobContextService, Mockito.never()).getCurrentContext(Mockito.any(), Mockito.any());
        assertEquals(List.of(Map.of("address", "a")), captor.getValue().get("planStructuredOutput/step-1"));
        assertTrue(captor.getValue().containsKey(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY));
        assertNull(captor.getValue().get(PlanStructuredOutputService.CONTEXT_NO_CHANGE_PLAN_KEY));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishRenderedPlanTextSavesItUnderItsOwnContextKeyByStep() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        PlanStructuredOutputService service = new PlanStructuredOutputService(jobContextService, new ObjectMapper(), new TerraformClient());

        service.publishRenderedPlanText("o", "1", "step-1", "  # aws_instance.foo will be created\n\nPlan: 1 to add, 0 to change, 0 to destroy.");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(jobContextService).patchContextFresh(Mockito.eq("o"), Mockito.eq("1"), captor.capture());
        Mockito.verify(jobContextService, Mockito.never()).getCurrentContext(Mockito.any(), Mockito.any());
        assertEquals(1, captor.getValue().size());
        String text = (String) captor.getValue().get(PlanStructuredOutputService.CONTEXT_PLAN_RENDERED_TEXT_KEY + "/step-1");
        assertTrue(text.contains("Plan: 1 to add, 0 to change, 0 to destroy."));
    }

    @Test
    void publishRenderedPlanTextDoesNothingForBlankText() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        PlanStructuredOutputService service = new PlanStructuredOutputService(jobContextService, new ObjectMapper(), new TerraformClient());

        service.publishRenderedPlanText("o", "1", "step-1", "   ");
        service.publishRenderedPlanText("o", "1", "step-1", null);

        Mockito.verify(jobContextService, Mockito.never()).patchContextFresh(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void publishRenderedPlanTextSwallowsAContextFailure() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.patchContextFresh(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new RuntimeException("boom"));
        PlanStructuredOutputService service = new PlanStructuredOutputService(jobContextService, new ObjectMapper(), new TerraformClient());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.publishRenderedPlanText("o", "1", "step-1", "some diff"));
    }

    // #3602: the show -json summary used to be a single synchronous POST after the queue drain, so
    // one transient 503 left the stale live snapshot (often empty + noChangePlan) as the result.
    @Test
    void publishPlanSummaryGoesThroughTheRetryingQueueAsTheFinalSnapshotWhenAsyncEnabled() throws Exception {
        io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue queue =
                Mockito.mock(io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue.class);
        Mockito.when(queue.nextSequence("1")).thenReturn(7L);
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        io.terrakube.executor.configuration.ExecutorFlagsProperties flags =
                new io.terrakube.executor.configuration.ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(true);

        String planJson = "{\"resource_changes\":[{\"address\":\"aws_instance.example\",\"type\":\"aws_instance\","
                + "\"name\":\"example\",\"change\":{\"actions\":[\"update\"],\"before\":{\"memory\":4096},"
                + "\"after\":{\"memory\":6144}}}]}";
        TerraformClient terraformClient = Mockito.mock(TerraformClient.class);
        Mockito.when(terraformClient.showPlanJson(Mockito.any(), Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenAnswer(invocation -> {
                    Consumer<String> output = invocation.getArgument(1);
                    output.accept(planJson);
                    return CompletableFuture.completedFuture(true);
                });

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), terraformClient, queue, flags);

        TerraformJob job = new TerraformJob();
        job.setOrganizationId("o");
        job.setJobId("1");
        job.setStepId("step-1");
        job.setTerraformVersion("1.16.4");
        job.setEnvironmentVariables(new HashMap<>());

        service.publishPlanSummary(job, new File("/tmp"), List.of(), List.of());

        Mockito.verify(queue).submit(Mockito.argThat(s -> s.key().stepId().equals("step-1")
                && s.isFinalSnapshot()
                && s.getChanges().size() == 1
                && "aws_instance.example".equals(s.getChanges().get(0).get("address"))));
        Mockito.verify(jobContextService, Mockito.never()).patchContextFresh(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void publishFinalPlanSnapshotSynchronous_returnsAckedOnSuccess() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any()))
                .thenReturn(JobContextService.PatchResult.APPLIED);

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), new TerraformClient());

        String state = service.publishFinalPlanSnapshotSynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("ACKED", state);
        Mockito.verify(jobContextService, Mockito.times(1))
                .patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void publishFinalPlanSnapshotSynchronous_subsequentAttemptSkipped_returnsAcked() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any()))
                .thenReturn(JobContextService.PatchResult.FAILED)
                .thenReturn(JobContextService.PatchResult.SKIPPED);

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), new TerraformClient());

        String state = service.publishFinalPlanSnapshotSynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("ACKED", state);
        Mockito.verify(jobContextService, Mockito.times(2))
                .patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void publishFinalPlanSnapshotSynchronous_spoolsOnFailureWhenSpoolerAvailable() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any()))
                .thenReturn(JobContextService.PatchResult.FAILED);

        io.terrakube.executor.service.terraform.spool.ContextSpoolService contextSpoolService = Mockito.mock(io.terrakube.executor.service.terraform.spool.ContextSpoolService.class);
        Mockito.when(contextSpoolService.spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("plan"), Mockito.anyLong(), Mockito.any()))
                .thenReturn("context-spool/o/1/step-1-plan.json.gz");

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), new TerraformClient(), null, null, contextSpoolService);

        String state = service.publishFinalPlanSnapshotSynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("SPOOLED", state);
        Mockito.verify(jobContextService, Mockito.times(3))
                .patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any());
        Mockito.verify(contextSpoolService).spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("plan"), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void publishFinalPlanSnapshotSynchronous_initialAttemptSkipped_spoolsWhenSpoolerAvailable() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any()))
                .thenReturn(JobContextService.PatchResult.SKIPPED);

        io.terrakube.executor.service.terraform.spool.ContextSpoolService contextSpoolService = Mockito.mock(io.terrakube.executor.service.terraform.spool.ContextSpoolService.class);
        Mockito.when(contextSpoolService.spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("plan"), Mockito.anyLong(), Mockito.any()))
                .thenReturn("context-spool/o/1/step-1-plan.json.gz");

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), new TerraformClient(), null, null, contextSpoolService);

        String state = service.publishFinalPlanSnapshotSynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("SPOOLED", state);
        // Only 1 attempt because attempt 1 SKIPPED breaks immediately without retrying the same revision
        Mockito.verify(jobContextService, Mockito.times(1))
                .patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any());
        Mockito.verify(contextSpoolService).spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("plan"), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void publishFinalPlanSnapshotSynchronous_returnsNotDurableWhenSpoolerFails() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.patchContext(Mockito.eq("o"), Mockito.eq("1"), Mockito.anyLong(), Mockito.any()))
                .thenReturn(JobContextService.PatchResult.FAILED);

        io.terrakube.executor.service.terraform.spool.ContextSpoolService contextSpoolService = Mockito.mock(io.terrakube.executor.service.terraform.spool.ContextSpoolService.class);
        Mockito.when(contextSpoolService.spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("plan"), Mockito.anyLong(), Mockito.any()))
                .thenReturn(null);

        PlanStructuredOutputService service = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), new TerraformClient(), null, null, contextSpoolService);

        String state = service.publishFinalPlanSnapshotSynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("NOT_DURABLE", state);
    }

    @Test
    void nullTerraformJobIsHandledSafelyAcrossAllPlanMethods() throws Exception {
        PlanStructuredOutputService service = new PlanStructuredOutputService(
                Mockito.mock(JobContextService.class),
                new ObjectMapper(),
                Mockito.mock(TerraformClient.class));

        assertEquals("NOT_DURABLE", service.publishFinalPlanSynchronous(null, new File("/tmp"), List.of(), List.of()));
        assertEquals("NOT_DURABLE", service.publishFinalPlanSynchronous(null, new File("/tmp"), 1L, List.of(), List.of()));
        assertNull(service.getPlanAsJson(null, new File("/tmp")));
        assertNull(service.getPlanAsHumanText(null, new File("/tmp")));
        assertDoesNotThrow(() -> service.publishPlanSummary(null, new File("/tmp"), List.of(), List.of()));
    }

    @Test
    void structuredPlanOutputDoesNotCarryTheInputVariablesSection() throws Exception {
        String json = """
                {"format_version":"1.2",
                 "variables":{"password":{"value":"private-variable-value"}},
                 "resource_changes":[],
                 "output_changes":{}}
                """;
        List<Map<String, Object>> changes = subject().buildChangesFromPlanJson(json);
        assertFalse(new ObjectMapper().writeValueAsString(changes).contains("private-variable-value"));
    }
}
