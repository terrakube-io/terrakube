package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
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
    void clearsTheAfterUnknownFlagOnceAnAttributeIsResolved() {
        // The UI's diff renderer decides whether to show the "(known after apply)" placeholder
        // purely from this flag (see structuredPlan.ts / StructuredPlanOutput.tsx's
        // `afterUnknown === true` check) rather than from whether `after` itself is still null -
        // so leaving the flag at `true` here left the placeholder stuck forever even once the
        // real value had been resolved into `after` below.
        Map<String, Object> after = new HashMap<>();
        after.put("id", null);

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
                          "values": {"id": "4cdc25f5-37c5"}
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfterUnknown = (Map<String, Object>) change.get("afterUnknown");
        assertEquals(false, resolvedAfterUnknown.get("id"));
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
    void resolvesAnUnknownAttributeNestedInsideAListOfObjects() {
        // Terraform's afterUnknown mirrors after's exact shape - a list attribute's unknown-ness
        // is a parallel list of per-item markers, not a single top-level boolean - so an unknown
        // value on just one field of one list entry (network_ip here) previously never resolved:
        // the old top-level-only loop only ever looked at afterUnknown's direct keys, and
        // "network_interface" itself is never `true` when only a field inside it is unknown.
        Map<String, Object> networkInterface = new HashMap<>();
        networkInterface.put("device_index", 0);
        networkInterface.put("network_ip", null);

        Map<String, Object> after = new HashMap<>();
        after.put("network_interface", new ArrayList<>(List.of(networkInterface)));

        Map<String, Object> unknownInterfaceEntry = new HashMap<>();
        unknownInterfaceEntry.put("network_ip", true);
        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("network_interface", new ArrayList<>(List.of(unknownInterfaceEntry)));

        Map<String, Object> change = new HashMap<>();
        change.put("address", "google_compute_instance.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "google_compute_instance.example",
                          "values": {
                            "network_interface": [
                              {"device_index": 0, "network_ip": "10.0.0.5"}
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        List<?> interfaces = (List<?>) resolvedAfter.get("network_interface");
        Map<?, ?> resolvedInterface = (Map<?, ?>) interfaces.get(0);
        assertEquals("10.0.0.5", resolvedInterface.get("network_ip"));
        assertEquals(0, resolvedInterface.get("device_index"));

        Map<?, ?> resolvedAfterUnknown = (Map<?, ?>) change.get("afterUnknown");
        List<?> unknownInterfaces = (List<?>) resolvedAfterUnknown.get("network_interface");
        Map<?, ?> resolvedUnknownInterface = (Map<?, ?>) unknownInterfaces.get(0);
        assertEquals(false, resolvedUnknownInterface.get("network_ip"));
    }

    @Test
    void resolvesAnUnknownAttributeNestedInsideAMap() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("region", "us-east-1");
        settings.put("generated_name", null);

        Map<String, Object> after = new HashMap<>();
        after.put("settings", settings);

        Map<String, Object> unknownSettings = new HashMap<>();
        unknownSettings.put("generated_name", true);
        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("settings", unknownSettings);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "aws_thing.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "aws_thing.example",
                          "values": {
                            "settings": {"region": "us-east-1", "generated_name": "thing-8f2c1"}
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        Map<?, ?> resolvedSettings = (Map<?, ?>) resolvedAfter.get("settings");
        assertEquals("thing-8f2c1", resolvedSettings.get("generated_name"));
        assertEquals("us-east-1", resolvedSettings.get("region"));

        Map<?, ?> resolvedAfterUnknown = (Map<?, ?>) change.get("afterUnknown");
        Map<?, ?> resolvedUnknownSettings = (Map<?, ?>) resolvedAfterUnknown.get("settings");
        assertEquals(false, resolvedUnknownSettings.get("generated_name"));
    }

    @Test
    void neverResolvesANestedSensitiveUnknownAttribute() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("token", null);

        Map<String, Object> after = new HashMap<>();
        after.put("credentials", credentials);

        Map<String, Object> unknownCredentials = new HashMap<>();
        unknownCredentials.put("token", true);
        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("credentials", unknownCredentials);

        Map<String, Object> sensitiveCredentials = new HashMap<>();
        sensitiveCredentials.put("token", true);
        Map<String, Object> afterSensitive = new HashMap<>();
        afterSensitive.put("credentials", sensitiveCredentials);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "vendor_thing.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);
        change.put("afterSensitive", afterSensitive);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "vendor_thing.example",
                          "values": {
                            "credentials": {"token": "top-secret-real-token"}
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        Map<?, ?> resolvedCredentials = (Map<?, ?>) resolvedAfter.get("credentials");
        assertNull(resolvedCredentials.get("token"));

        Map<?, ?> resolvedAfterUnknown = (Map<?, ?>) change.get("afterUnknown");
        Map<?, ?> resolvedUnknownCredentials = (Map<?, ?>) resolvedAfterUnknown.get("credentials");
        assertEquals(true, resolvedUnknownCredentials.get("token"));
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

    @Test
    void neverResolvesNestedAttributesWhenParentContainerIsSensitive() {
        // Container-level sensitivity: afterSensitive is `true` for a parent map/block,
        // while afterUnknown contains nested field entries.
        Map<String, Object> after = new HashMap<>();
        after.put("input", null);

        Map<String, Object> unknownInput = new HashMap<>();
        unknownInput.put("secret_token", true);
        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("input", unknownInput);

        Map<String, Object> afterSensitive = new HashMap<>();
        afterSensitive.put("input", true);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "terraform_data.db_credentials");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);
        change.put("afterSensitive", afterSensitive);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "terraform_data.db_credentials",
                          "values": {
                            "input": {
                              "username": "admin",
                              "secret_token": "super-secret-password-123",
                              "endpoint_port": 5432
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        assertNull(resolvedAfter.get("input"));

        Map<String, Object> resolvedAfterUnknown = (Map<String, Object>) change.get("afterUnknown");
        assertEquals(unknownInput, resolvedAfterUnknown.get("input"));
    }

    @Test
    void neverResolvesNestedListAttributesWhenParentListIsSensitive() {
        Map<String, Object> after = new HashMap<>();
        after.put("items", null);

        Map<String, Object> unknownItem = new HashMap<>();
        unknownItem.put("key", true);
        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("items", new ArrayList<>(List.of(unknownItem)));

        Map<String, Object> afterSensitive = new HashMap<>();
        afterSensitive.put("items", true);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "custom_resource.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);
        change.put("afterSensitive", afterSensitive);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "custom_resource.example",
                          "values": {
                            "items": [
                              {"key": "sensitive-item-key"}
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        assertNull(resolvedAfter.get("items"));
    }

    @Test
    void sanitizesResolvedNestedSensitiveAttributesWhenLeafIsUnknown() {
        // Leaf unknown resolving to a map containing sensitive subfields
        Map<String, Object> after = new HashMap<>();
        after.put("credentials", null);

        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("credentials", true);

        Map<String, Object> sensitiveCredentials = new HashMap<>();
        sensitiveCredentials.put("secret_key", true);
        sensitiveCredentials.put("public_id", false);
        Map<String, Object> afterSensitive = new HashMap<>();
        afterSensitive.put("credentials", sensitiveCredentials);

        Map<String, Object> change = new HashMap<>();
        change.put("address", "custom_vault.example");
        change.put("after", after);
        change.put("afterUnknown", afterUnknown);
        change.put("afterSensitive", afterSensitive);

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        {
                          "address": "custom_vault.example",
                          "values": {
                            "credentials": {
                              "public_id": "pub-12345",
                              "secret_key": "sec-98765"
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        Map<?, ?> resolvedCredentials = (Map<?, ?>) resolvedAfter.get("credentials");
        assertEquals("pub-12345", resolvedCredentials.get("public_id"));
        assertNull(resolvedCredentials.get("secret_key"));

        Map<String, Object> resolvedAfterUnknown = (Map<String, Object>) change.get("afterUnknown");
        assertEquals(false, resolvedAfterUnknown.get("credentials"));
    }
}
