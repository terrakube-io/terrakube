package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyStructuredOutputServiceTest {

    private ApplyStructuredOutputService subject() {
        return new ApplyStructuredOutputService(
                Mockito.mock(JobContextService.class),
                new ObjectMapper());
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
    void doesNotSeedOutputChangesBecauseApplyNeverUpdatesThem() {
        Map<String, Object> context = new HashMap<>();
        context.put("planStructuredOutput", Map.of(
                "plan-step-1", List.of(
                        Map.of("address", "aws_instance.example", "action", "create"),
                        Map.of("address", "derived_value", "action", "update", "isOutputChange", true))));

        List<Map<String, Object>> seeded = subject().seedFromPlan(context);

        assertEquals(1, seeded.size());
        assertEquals("aws_instance.example", seeded.get(0).get("address"));
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
    void buildApplyPatchTouchesOnlyThisStepsEntries() {
        List<Map<String, Object>> changes = List.of(Map.of("address", "aws_instance.foo", "status", "applied"));
        List<Map<String, Object>> jobDiagnostics = List.of(Map.of("severity", "warning", "summary", "deprecated argument"));

        Map<String, Object> set = subject().buildApplyPatch("step-1", changes, jobDiagnostics);

        assertEquals(Map.of("applyStructuredOutput/step-1", changes, "jobDiagnostics/step-1", jobDiagnostics), set);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishFinalApplySnapshotPatchesWithoutReadingWhenSynchronous() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        ApplyStructuredOutputService service = new ApplyStructuredOutputService(jobContextService, new ObjectMapper());
        List<Map<String, Object>> changes = List.of(Map.of("address", "aws_instance.foo", "status", "applied"));

        service.publishFinalApplySnapshot("o", "1", "step-1", changes, List.of());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(jobContextService).patchContextFresh(Mockito.eq("o"), Mockito.eq("1"), captor.capture());
        Mockito.verify(jobContextService, Mockito.never()).getCurrentContext(Mockito.any(), Mockito.any());
        assertEquals(changes, captor.getValue().get("applyStructuredOutput/step-1"));
        assertFalse(captor.getValue().containsKey("structuredOutputStatus"));
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

    @Test
    void sanitizesDynamicallyComputedOutputsFromStateSensitiveValues() {
        // Plan-time had empty afterSensitive for computed output, but stateJson contains
        // post-apply sensitive_values computed by Terraform.
        Map<String, Object> after = new HashMap<>();
        after.put("id", null);
        after.put("input", null);
        after.put("output", null);

        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("id", true);
        afterUnknown.put("input", Map.of("secret_token", true));
        afterUnknown.put("output", true);

        Map<String, Object> afterSensitive = new HashMap<>();
        afterSensitive.put("input", true);
        afterSensitive.put("output", new HashMap<>());

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
                            "id": "130fa94e-30ad-fcd3-76ee-884a8af93d42",
                            "input": {
                              "endpoint_port": 5432,
                              "secret_token": "super-secret-password-123",
                              "username": "admin"
                            },
                            "output": {
                              "endpoint_port": 5432,
                              "secret_token": "super-secret-password-123",
                              "username": "admin"
                            }
                          },
                          "sensitive_values": {
                            "input": true,
                            "output": {
                              "secret_token": true
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
        assertEquals("130fa94e-30ad-fcd3-76ee-884a8af93d42", resolvedAfter.get("id"));
        assertNull(resolvedAfter.get("input"));

        Map<?, ?> resolvedOutput = (Map<?, ?>) resolvedAfter.get("output");
        assertEquals("admin", resolvedOutput.get("username"));
        assertEquals(5432, resolvedOutput.get("endpoint_port"));
        assertNull(resolvedOutput.get("secret_token"));

        Map<?, ?> resolvedSensitive = (Map<?, ?>) change.get("afterSensitive");
        assertEquals(true, resolvedSensitive.get("input"));
        Map<?, ?> outputSensitive = (Map<?, ?>) resolvedSensitive.get("output");
        assertEquals(true, outputSensitive.get("secret_token"));
    }

    @Test
    void propagatesTerraformDataInputSensitivityToOutputWhenOutputSensitivityIsEmpty() {
        // Reproduces the exact environment scenario where terraform_data.output has empty afterSensitive {}
        // and state.json does not mark output sensitive in sensitive_values.
        Map<String, Object> after = new HashMap<>();
        after.put("id", null);
        after.put("input", null);
        after.put("output", null);

        Map<String, Object> afterUnknown = new HashMap<>();
        afterUnknown.put("id", true);
        afterUnknown.put("input", Map.of("secret_token", true));
        afterUnknown.put("output", true);

        Map<String, Object> afterSensitive = new HashMap<>();
        afterSensitive.put("input", true);
        afterSensitive.put("output", new HashMap<>());

        Map<String, Object> change = new HashMap<>();
        change.put("address", "terraform_data.db_credentials");
        change.put("resourceType", "terraform_data");
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
                            "id": "130fa94e-30ad-fcd3-76ee-884a8af93d42",
                            "input": {
                              "endpoint_port": 5432,
                              "secret_token": "super-secret-password-123",
                              "username": "admin"
                            },
                            "output": {
                              "endpoint_port": 5432,
                              "secret_token": "super-secret-password-123",
                              "username": "admin"
                            }
                          },
                          "sensitive_values": {
                            "input": true
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(change), stateJson);

        Map<String, Object> resolvedAfter = (Map<String, Object>) change.get("after");
        assertEquals("130fa94e-30ad-fcd3-76ee-884a8af93d42", resolvedAfter.get("id"));
        assertNull(resolvedAfter.get("input"));
        assertNull(resolvedAfter.get("output"));

        Map<?, ?> resolvedSensitive = (Map<?, ?>) change.get("afterSensitive");
        assertEquals(true, resolvedSensitive.get("input"));
        assertEquals(true, resolvedSensitive.get("output"));
    }

    @Test
    void finalizeResourceStatusesTransitionsInProgressToInterruptedWhenInterrupted() {
        List<Map<String, Object>> changes = new ArrayList<>();

        for (String inProgressStatus : List.of("applying", "provisioning", "refreshing", "reading", "ephemeral-opening", "ephemeral-closing")) {
            Map<String, Object> c = new HashMap<>();
            c.put("address", "res." + inProgressStatus);
            c.put("status", inProgressStatus);
            if ("refreshing".equals(inProgressStatus)) {
                c.put("previousStatus", "planned");
            }
            changes.add(c);
        }

        Map<String, Object> applied = new HashMap<>();
        applied.put("address", "res.applied");
        applied.put("status", "applied");
        changes.add(applied);

        Map<String, Object> errored = new HashMap<>();
        errored.put("address", "res.errored");
        errored.put("status", "errored");
        changes.add(errored);

        Map<String, Object> pending = new HashMap<>();
        pending.put("address", "res.pending");
        pending.put("status", "pending");
        changes.add(pending);

        Map<String, Object> planned = new HashMap<>();
        planned.put("address", "res.planned");
        planned.put("status", "planned");
        changes.add(planned);

        subject().finalizeResourceStatuses(changes, false, true);

        for (int i = 0; i < 6; i++) {
            assertEquals("interrupted", changes.get(i).get("status"));
            assertNull(changes.get(i).get("previousStatus"));
        }
        assertEquals("applied", applied.get("status"));
        assertEquals("errored", errored.get("status"));
        assertEquals("not-started", pending.get("status"));
        assertEquals("planned", planned.get("status"));
    }

    @Test
    void finalizeResourceStatusesTransitionsInProgressToErroredOnUninterruptedFailure() {
        Map<String, Object> applying = new HashMap<>();
        applying.put("address", "res.applying");
        applying.put("status", "applying");

        Map<String, Object> applied = new HashMap<>();
        applied.put("address", "res.applied");
        applied.put("status", "applied");

        Map<String, Object> pending = new HashMap<>();
        pending.put("address", "res.pending");
        pending.put("status", "pending");

        subject().finalizeResourceStatuses(List.of(applying, applied, pending), false, false);

        assertEquals("errored", applying.get("status"));
        assertEquals("applied", applied.get("status"));
        assertEquals("not-started", pending.get("status"));
    }

    @Test
    void finalizeResourceStatusesDoesNothingOnSuccessfulExecution() {
        Map<String, Object> applying = new HashMap<>();
        applying.put("address", "res.applying");
        applying.put("status", "applying");

        Map<String, Object> pending = new HashMap<>();
        pending.put("address", "res.pending");
        pending.put("status", "pending");

        subject().finalizeResourceStatuses(List.of(applying, pending), true, false);

        assertEquals("applying", applying.get("status"));
        assertEquals("pending", pending.get("status"));
    }

    @Test
    void resolveFinalValuesPromotesOnlyPendingImportToAppliedWhenPresentInState() {
        Map<String, Object> importChange = new HashMap<>();
        importChange.put("address", "aws_instance.imported");
        importChange.put("action", "import");
        importChange.put("status", "pending");

        Map<String, Object> updateChange = new HashMap<>();
        updateChange.put("address", "aws_instance.existing_update");
        updateChange.put("action", "update");
        updateChange.put("status", "pending");

        Map<String, Object> deleteChange = new HashMap<>();
        deleteChange.put("address", "aws_instance.existing_delete");
        deleteChange.put("action", "delete");
        deleteChange.put("status", "pending");

        String stateJson = """
                {
                  "values": {
                    "root_module": {
                      "resources": [
                        { "address": "aws_instance.imported", "values": { "id": "i-imp" } },
                        { "address": "aws_instance.existing_update", "values": { "id": "i-upd" } },
                        { "address": "aws_instance.existing_delete", "values": { "id": "i-del" } }
                      ]
                    }
                  }
                }
                """;

        subject().resolveFinalValues(List.of(importChange, updateChange, deleteChange), stateJson);

        assertEquals("applied", importChange.get("status"), "Pending import found in state must be promoted to applied");
        assertEquals("pending", updateChange.get("status"), "Pending update already in state must NOT be promoted to applied");
        assertEquals("pending", deleteChange.get("status"), "Pending delete still in state must NOT be promoted to applied");
    }

    @Test
    void publishFinalApplySynchronous_returnsAckedOnSuccess() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.getCurrentContext("o", "1")).thenReturn(new HashMap<>());
        Mockito.when(jobContextService.saveContextChecked(Mockito.eq("o"), Mockito.eq("1"), Mockito.any())).thenReturn(true);

        ApplyStructuredOutputService service = new ApplyStructuredOutputService(
                jobContextService, new ObjectMapper());

        String state = service.publishFinalApplySynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("ACKED", state);
        Mockito.verify(jobContextService, Mockito.times(1)).saveContextChecked(Mockito.eq("o"), Mockito.eq("1"), Mockito.any());
    }

    @Test
    void publishFinalApplySynchronous_spoolsOnFailureWhenSpoolerAvailable() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.getCurrentContext("o", "1")).thenReturn(new HashMap<>());
        Mockito.when(jobContextService.saveContextChecked(Mockito.eq("o"), Mockito.eq("1"), Mockito.any())).thenReturn(false);

        io.terrakube.executor.service.terraform.spool.ContextSpoolService contextSpoolService = Mockito.mock(io.terrakube.executor.service.terraform.spool.ContextSpoolService.class);
        Mockito.when(contextSpoolService.spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("apply"), Mockito.anyLong(), Mockito.any()))
                .thenReturn("context-spool/o/1/step-1-apply.json.gz");

        ApplyStructuredOutputService service = new ApplyStructuredOutputService(
                jobContextService, new ObjectMapper(), null, null, contextSpoolService);

        String state = service.publishFinalApplySynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("SPOOLED", state);
        Mockito.verify(jobContextService, Mockito.times(3)).saveContextChecked(Mockito.eq("o"), Mockito.eq("1"), Mockito.any());
        Mockito.verify(contextSpoolService).spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("apply"), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void publishFinalApplySynchronous_returnsNotDurableWhenSpoolerFails() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        Mockito.when(jobContextService.getCurrentContext("o", "1")).thenReturn(new HashMap<>());
        Mockito.when(jobContextService.saveContextChecked(Mockito.eq("o"), Mockito.eq("1"), Mockito.any())).thenReturn(false);

        io.terrakube.executor.service.terraform.spool.ContextSpoolService contextSpoolService = Mockito.mock(io.terrakube.executor.service.terraform.spool.ContextSpoolService.class);
        Mockito.when(contextSpoolService.spoolContextPatch(Mockito.eq("o"), Mockito.eq("1"), Mockito.eq("step-1"), Mockito.eq("apply"), Mockito.anyLong(), Mockito.any()))
                .thenReturn(null);

        ApplyStructuredOutputService service = new ApplyStructuredOutputService(
                jobContextService, new ObjectMapper(), null, null, contextSpoolService);

        String state = service.publishFinalApplySynchronous("o", "1", "step-1", List.of(), List.of());
        assertEquals("NOT_DURABLE", state);
    }
}
