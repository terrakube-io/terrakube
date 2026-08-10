package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyStructuredOutputServiceTest {

    private ApplyStructuredOutputService subject() {
        return new ApplyStructuredOutputService(
                Mockito.mock(WorkspaceSecurity.class),
                new ObjectMapper(),
                "http://terrakube-api",
                Mockito.mock(TerrakubeClient.class));
    }

    @Test
    void seedsPendingStatusFromTheSolePlanStepEntry() {
        Map<String, Object> context = new HashMap<>();
        context.put("planStructuredOutput", Map.of(
                "plan-step-1", List.of(Map.of("address", "aws_instance.example", "action", "create"))));

        List<Map<String, Object>> seeded = subject().seedFromPlan(context);

        assertEquals(1, seeded.size());
        assertEquals("aws_instance.example", seeded.get(0).get("address"));
        assertEquals("pending", seeded.get(0).get("status"));
    }

    @Test
    void keepsAnEphemeralResourcesPlanTimeStatusInsteadOfResettingToPending() {
        // Unlike managed resources, apply -json emits no ephemeral_op/action event at all for an
        // ephemeral resource that nothing else in the config references - its whole open/close
        // lifecycle already happened during planning. Resetting its status to "pending" here would
        // leave it stuck showing that even after a successful apply, since nothing during apply
        // would ever update it again.
        Map<String, Object> context = new HashMap<>();
        context.put("planStructuredOutput", Map.of(
                "plan-step-1", List.of(Map.of(
                        "address", "ephemeral.random_password.session_secret",
                        "action", "ephemeral",
                        "status", "applied"))));

        List<Map<String, Object>> seeded = subject().seedFromPlan(context);

        assertEquals(1, seeded.size());
        assertEquals("applied", seeded.get(0).get("status"));
    }

    @Test
    void skipsSeedingWhenThereIsNoPlanStructuredOutput() {
        List<Map<String, Object>> seeded = subject().seedFromPlan(new HashMap<>());

        assertTrue(seeded.isEmpty());
    }

    @Test
    void skipsSeedingWhenMultiplePlanStepsExist() {
        Map<String, Object> context = new HashMap<>();
        context.put("planStructuredOutput", Map.of(
                "plan-step-1", List.of(Map.of("address", "aws_instance.a", "action", "create")),
                "plan-step-2", List.of(Map.of("address", "aws_instance.b", "action", "create"))));

        List<Map<String, Object>> seeded = subject().seedFromPlan(context);

        assertTrue(seeded.isEmpty());
    }

    @Test
    void updateApplyContextMergesWithoutDroppingExistingKeys() {
        Map<String, Object> context = new HashMap<>();
        context.put("custom", "value");
        context.put("applyStructuredOutput", Map.of("existing-step", List.of(Map.of("address", "existing"))));

        Map<String, Object> updated = subject().updateApplyContext(
                context, "new-step", List.of(Map.of("address", "aws_instance.new", "status", "pending")), List.of());

        assertEquals("value", updated.get("custom"));
        Map<String, Object> applyOutput = (Map<String, Object>) updated.get("applyStructuredOutput");
        assertTrue(applyOutput.containsKey("existing-step"));
        assertTrue(applyOutput.containsKey("new-step"));
    }

    @Test
    void publishApplyProgressIncludesJobDiagnosticsInSavedContext() {
        List<Map<String, Object>> changes = List.of(Map.of("address", "aws_instance.foo", "status", "applied"));
        List<Map<String, Object>> jobDiagnostics = List.of(Map.of("severity", "warning", "summary", "deprecated argument"));

        Map<String, Object> context = new HashMap<>();
        Map<String, Object> updated = subject().updateApplyContext(context, "step-1", changes, jobDiagnostics);

        Map<String, Object> applyOutput = (Map<String, Object>) updated.get("applyStructuredOutput");
        assertEquals(changes, applyOutput.get("step-1"));

        Map<String, Object> jobDiagnosticsOutput = (Map<String, Object>) updated.get("jobDiagnostics");
        assertEquals(jobDiagnostics, jobDiagnosticsOutput.get("step-1"));
    }

    @Test
    void resolvesUnknownAttributesFromCurrentStateJson() {
        Map<String, Object> after = new HashMap<>();
        after.put("id", null);
        after.put("input", "hello");

        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("id", true);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "terraform_data.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "terraform_data.example",
                          "values": {"id": "4cdc25f5-37c5", "input": "hello"}
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        assertEquals("4cdc25f5-37c5", resolvedAfter.get("id"));
    }

    @Test
    void neverResolvesASensitiveUnknownAttributeToItsRealValue() {
        Map<String, Object> after = new HashMap<>();
        after.put("result", null);

        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("result", true);

        Map<String, Object> afterSensitive = new HashMap<>();
        afterSensitive.put("result", true);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "random_password.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);
        change.put("afterSensitive", afterSensitive);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "random_password.example",
                          "values": {"result": "top-secret-real-value"}
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        assertNull(resolvedAfter.get("result"));
    }

    @Test
    void resolvesAttributesForResourcesInsideNestedModules() {
        Map<String, Object> after = new HashMap<>();
        after.put("id", null);

        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("id", true);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "module.child.terraform_data.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [],
                      "child_modules": [
                        {
                          "address": "module.child",
                          "resources": [
                            {
                              "address": "module.child.terraform_data.example",
                              "values": {"id": "nested-id"}
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        assertEquals("nested-id", resolvedAfter.get("id"));
    }

    @Test
    void marksAnImportAsAppliedWhenItNeverReceivedAnApplyJsonEvent() {
        // Terraform's `apply -json` stream never emits apply_start/apply_complete hook
        // events for resources handled by a config-driven `import` block (it's a distinct
        // PreApplyImport/PostApplyImport hook pair that the JSON view doesn't wire up), so
        // ApplyJsonEventParser has nothing to key off of and the row stays "pending" forever.
        // Its presence in the post-apply state is the only signal we have that it succeeded.
        Map<String, Object> after = new HashMap<>();
        after.put("id", "already-known-at-plan-time");

        Map<String, Object> change = new HashMap<>();
        change.put("address", "random_string.imported_example");
        change.put("action", "import");
        change.put("status", "pending");
        change.put("after", after);
        change.put("afterUnknown", new HashMap<>());

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "random_string.imported_example",
                          "values": {"id": "already-known-at-plan-time"}
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        assertEquals("applied", change.get("status"));
    }
}
