package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformJsonEventParserTest {

    private TerraformJsonEventParser subject() {
        return new TerraformJsonEventParser(new ObjectMapper());
    }

    private List<Map<String, Object>> oneChange(String address) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> change = new HashMap<>();
        change.put("address", address);
        change.put("status", "pending");
        changes.add(change);
        return changes;
    }

    @Test
    void marksResourceApplyingOnApplyStart() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        String message = subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Creating...\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"action\":\"create\"},\"type\":\"apply_start\"}",
                changes, jobDiagnostics);

        assertEquals("applying", changes.get(0).get("status"));
        assertEquals("aws_instance.foo: Creating...", message);
    }

    @Test
    void marksResourceAppliedOnApplyComplete() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Creating...\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"action\":\"create\"},\"type\":\"apply_start\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Creation complete after 0s [id=abc]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"action\":\"create\",\"id_key\":\"id\",\"id_value\":\"abc\",\"elapsed_seconds\":0},\"type\":\"apply_complete\"}",
                changes, jobDiagnostics);

        assertEquals("applied", changes.get(0).get("status"));
    }

    @Test
    void marksResourceErroredAndAttachesDiagnosticMessage() {
        List<Map<String, Object>> changes = oneChange("null_resource.fails");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"null_resource.fails: Creation errored after 0s\",\"hook\":{\"resource\":{\"addr\":\"null_resource.fails\"},\"action\":\"create\",\"elapsed_seconds\":0},\"type\":\"apply_errored\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"Error: local-exec provisioner error\",\"diagnostic\":{\"severity\":\"error\",\"summary\":\"local-exec provisioner error\",\"detail\":\"Error running command\",\"address\":\"null_resource.fails\"},\"type\":\"diagnostic\"}",
                changes, jobDiagnostics);

        assertEquals("errored", changes.get(0).get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) changes.get(0).get("diagnostics");
        assertEquals("local-exec provisioner error", diagnostics.get(0).get("summary"));
    }

    // Under `-json` a diagnostic's own `@message` is only the one-line "Error: <summary>" header -
    // the file, line, source snippet and explanation live in the structured `diagnostic` object.
    // parseLine must hand the caller the full multi-line rendering that plain `tofu plan` prints,
    // otherwise the CLI (and raw-log download, and PR comment) show a bare "Error: Unsupported
    // attribute" with nothing else.
    @Test
    void returnsFullHumanReadableDiagnosticRenderingForTheConsole() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        String consoleText = subject().parseLine(
                "{\"@message\":\"Error: Unsupported attribute\",\"@level\":\"error\",\"type\":\"diagnostic\","
                        + "\"diagnostic\":{\"severity\":\"error\",\"summary\":\"Unsupported attribute\","
                        + "\"detail\":\"This object has no argument, nested block, or exported attribute named \\\"identifier\\\".\","
                        + "\"range\":{\"filename\":\"main.tf\",\"start\":{\"line\":12,\"column\":12},\"end\":{\"line\":12,\"column\":30}},"
                        + "\"snippet\":{\"context\":\"resource \\\"aws_instance\\\" \\\"web\\\"\",\"code\":\"  subnet = aws_subnet.main.identifier\",\"start_line\":12}}}",
                changes, jobDiagnostics);

        assertTrue(consoleText.contains("Error: Unsupported attribute"), consoleText);
        assertTrue(consoleText.contains("on main.tf line 12, in resource \"aws_instance\" \"web\""), consoleText);
        assertTrue(consoleText.contains("12:   subnet = aws_subnet.main.identifier"), consoleText);
        assertTrue(consoleText.contains(
                "This object has no argument, nested block, or exported attribute named \"identifier\"."), consoleText);
    }

    @Test
    void rendersADiagnosticWithNoSourceLocationAsJustSummaryAndDetail() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        String consoleText = subject().parseLine(
                "{\"@message\":\"Warning: Deprecated attribute\",\"type\":\"diagnostic\","
                        + "\"diagnostic\":{\"severity\":\"warning\",\"summary\":\"Deprecated attribute\","
                        + "\"detail\":\"The attribute \\\"foo\\\" is deprecated. Use \\\"bar\\\" instead.\"}}",
                changes, jobDiagnostics);

        assertTrue(consoleText.contains("Warning: Deprecated attribute"), consoleText);
        assertTrue(consoleText.contains("The attribute \"foo\" is deprecated. Use \"bar\" instead."), consoleText);
        assertFalse(consoleText.contains("  on "), consoleText);
    }

    @Test
    void returnsMessageForNonHookEventsAndOriginalLineForUnparsableLines() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        String message = subject().parseLine(
                "{\"@message\":\"Apply complete! Resources: 1 added, 0 changed, 0 destroyed.\",\"type\":\"change_summary\"}",
                changes, jobDiagnostics);

        assertEquals("Apply complete! Resources: 1 added, 0 changed, 0 destroyed.", message);
        assertEquals("not valid json", subject().parseLine("not valid json", changes, jobDiagnostics));
    }

    @Test
    void attachesMultipleDiagnosticsOfDifferentSeverityToOneResource() {
        List<Map<String, Object>> changes = oneChange("null_resource.fails");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"Warning: deprecated argument\",\"diagnostic\":{\"severity\":\"warning\",\"summary\":\"deprecated argument\",\"address\":\"null_resource.fails\"},\"type\":\"diagnostic\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"Error: local-exec provisioner error\",\"diagnostic\":{\"severity\":\"error\",\"summary\":\"local-exec provisioner error\",\"detail\":\"Error running command\",\"address\":\"null_resource.fails\"},\"type\":\"diagnostic\"}",
                changes, jobDiagnostics);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) changes.get(0).get("diagnostics");
        assertEquals(2, diagnostics.size());
        assertEquals("warning", diagnostics.get(0).get("severity"));
        assertEquals("deprecated argument", diagnostics.get(0).get("summary"));
        assertEquals("error", diagnostics.get(1).get("severity"));
        assertEquals("local-exec provisioner error", diagnostics.get(1).get("summary"));
        assertEquals("Error running command", diagnostics.get(1).get("detail"));
        assertTrue(jobDiagnostics.isEmpty());
    }

    @Test
    void routesUnaddressedDiagnosticsToJobLevelList() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"Warning: argument is deprecated\",\"diagnostic\":{\"severity\":\"warning\",\"summary\":\"argument is deprecated\"},\"type\":\"diagnostic\"}",
                changes, jobDiagnostics);

        assertEquals(1, jobDiagnostics.size());
        assertEquals("warning", jobDiagnostics.get(0).get("severity"));
        assertNull(changes.get(0).get("diagnostics"));
    }

    // Regression test: a resource that errors before Terraform ever emits a planned_change for
    // it (e.g. a provider that can't authenticate, so evaluation aborts before an action is
    // determined) previously vanished from the structured panel entirely - attachDiagnostic
    // returned without recording anything once it found no matching "changes" entry, unlike
    // applyPlannedChange, which seeds a new row for an address it hasn't seen yet.
    @Test
    void seedsAResourceRowWhenAnErrorDiagnosticArrivesForAnUnseenAddress() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"Error: No valid credential sources found\",\"diagnostic\":{\"severity\":\"error\",\"summary\":\"No valid credential sources found\",\"detail\":\"please see...\",\"address\":\"module.this.module.inner.aws_secretsmanager_secret.test\"},\"type\":\"diagnostic\"}",
                changes, jobDiagnostics);

        assertEquals(1, changes.size());
        assertEquals("module.this.module.inner.aws_secretsmanager_secret.test", changes.get(0).get("address"));
        assertEquals("errored", changes.get(0).get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) changes.get(0).get("diagnostics");
        assertEquals("No valid credential sources found", diagnostics.get(0).get("summary"));
        assertTrue(jobDiagnostics.isEmpty());
    }

    // A diagnostic's "range" (file + line) is the only location Terraform gives us for
    // diagnostics that carry no resource address at all (e.g. a deprecated variable/output,
    // which can be referenced from many places) - surface it so two textually-identical
    // unaddressed warnings can still be told apart in the UI.
    @Test
    void capturesFileAndLineFromDiagnosticRange() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"Warning: Deprecated variable got a value\",\"diagnostic\":{\"severity\":\"warning\",\"summary\":\"Deprecated variable got a value\",\"detail\":\"use `new_flag` instead\",\"range\":{\"filename\":\"variables.tf\",\"start\":{\"line\":4,\"column\":3},\"end\":{\"line\":4,\"column\":40}}},\"type\":\"diagnostic\"}",
                changes, jobDiagnostics);

        assertEquals(1, jobDiagnostics.size());
        assertEquals("variables.tf:4", jobDiagnostics.get(0).get("location"));
    }

    @Test
    void updatesElapsedSecondsOnProgressAndCompleteEvents() {
        List<Map<String, Object>> changes = oneChange("aws_cloudfront_distribution.this");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"aws_cloudfront_distribution.this: Still creating... [1m30s elapsed]\",\"hook\":{\"resource\":{\"addr\":\"aws_cloudfront_distribution.this\"},\"action\":\"create\",\"elapsed_seconds\":90},\"type\":\"apply_progress\"}",
                changes, jobDiagnostics);
        assertEquals(90, changes.get(0).get("elapsedSeconds"));

        subject().parseLine(
                "{\"@message\":\"aws_cloudfront_distribution.this: Creation complete after 3m0s\",\"hook\":{\"resource\":{\"addr\":\"aws_cloudfront_distribution.this\"},\"action\":\"create\",\"elapsed_seconds\":180},\"type\":\"apply_complete\"}",
                changes, jobDiagnostics);
        assertEquals(180, changes.get(0).get("elapsedSeconds"));
        assertEquals("applied", changes.get(0).get("status"));
    }

    @Test
    void tracksProvisionerLifecycleAndOutput() {
        List<Map<String, Object>> changes = oneChange("null_resource.script");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"null_resource.script: Provisioning with 'local-exec'...\",\"hook\":{\"resource\":{\"addr\":\"null_resource.script\"},\"provisioner\":\"local-exec\"},\"type\":\"provision_start\"}",
                changes, jobDiagnostics);
        assertEquals("local-exec", changes.get(0).get("currentProvisioner"));

        subject().parseLine(
                "{\"@message\":\"null_resource.script (local-exec): Hello from script\",\"hook\":{\"resource\":{\"addr\":\"null_resource.script\"},\"provisioner\":\"local-exec\",\"output\":\"Hello from script\"},\"type\":\"provision_progress\"}",
                changes, jobDiagnostics);

        @SuppressWarnings("unchecked")
        List<String> output = (List<String>) changes.get(0).get("provisionerOutput");
        assertEquals(List.of("Hello from script"), output);

        subject().parseLine(
                "{\"@message\":\"null_resource.script: (local-exec) Provisioning complete\",\"hook\":{\"resource\":{\"addr\":\"null_resource.script\"},\"provisioner\":\"local-exec\"},\"type\":\"provision_complete\"}",
                changes, jobDiagnostics);
        assertNull(changes.get(0).get("currentProvisioner"));
    }

    @Test
    void refreshRestoresThePriorStatusOnComplete() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");
        changes.get(0).put("status", "applying");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Refreshing state... [id=i-1]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"id_key\":\"id\",\"id_value\":\"i-1\"},\"type\":\"refresh_start\"}",
                changes, jobDiagnostics);
        assertEquals("refreshing", changes.get(0).get("status"));

        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Refresh complete [id=i-1]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"id_key\":\"id\",\"id_value\":\"i-1\"},\"type\":\"refresh_complete\"}",
                changes, jobDiagnostics);
        assertEquals("applying", changes.get(0).get("status"));
        assertNull(changes.get(0).get("previousStatus"));
    }

    @Test
    void refreshOnAPendingResourceRestoresPending() {
        List<Map<String, Object>> changes = oneChange("aws_instance.foo");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Refreshing state... [id=i-1]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"id_key\":\"id\",\"id_value\":\"i-1\"},\"type\":\"refresh_start\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Refresh complete [id=i-1]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"id_key\":\"id\",\"id_value\":\"i-1\"},\"type\":\"refresh_complete\"}",
                changes, jobDiagnostics);

        assertEquals("pending", changes.get(0).get("status"));
    }

    @Test
    void tracksEphemeralResourceLifecycle() {
        List<Map<String, Object>> changes = oneChange("random_password.ephemeral_secret");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"random_password.ephemeral_secret: Opening...\",\"hook\":{\"resource\":{\"addr\":\"random_password.ephemeral_secret\"},\"action\":\"open\"},\"type\":\"ephemeral_op_start\"}",
                changes, jobDiagnostics);
        assertEquals("ephemeral-opening", changes.get(0).get("status"));

        subject().parseLine(
                "{\"@message\":\"random_password.ephemeral_secret: Opening complete after 0s\",\"hook\":{\"resource\":{\"addr\":\"random_password.ephemeral_secret\"},\"action\":\"open\",\"elapsed_seconds\":0},\"type\":\"ephemeral_op_complete\"}",
                changes, jobDiagnostics);
        assertEquals("applied", changes.get(0).get("status"));
    }

    // OpenTofu (as of 1.12.5) uses a completely different event vocabulary for ephemeral
    // resources than Terraform: "ephemeral_action_started"/"ephemeral_action_complete" instead
    // of "ephemeral_op_start"/"ephemeral_op_complete"/"ephemeral_op_errored", and the hook has no
    // "action" field at all - only a human-text "Msg" field ("Opening..."/"Renewing..."/
    // "Closing..." and their "complete" counterparts). JSON captured directly from
    // `tofu plan -json` against an `ephemeral "random_password"` block. Reproduces the bug where
    // ephemeral resources never appeared in the structured output panel at all on OpenTofu
    // workspaces, even though the same feature worked on Terraform workspaces.
    @Test
    void tracksEphemeralResourceLifecycleUsingOpenTofusEventVocabulary() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"ephemeral.random_password.session_secret: Opening...\",\"hook\":{\"resource\":{\"addr\":\"ephemeral.random_password.session_secret\"},\"Msg\":\"Opening...\"},\"type\":\"ephemeral_action_started\"}",
                changes, jobDiagnostics);
        assertEquals(1, changes.size());
        assertEquals("ephemeral-opening", changes.get(0).get("status"));

        subject().parseLine(
                "{\"@message\":\"ephemeral.random_password.session_secret: Open complete\",\"hook\":{\"resource\":{\"addr\":\"ephemeral.random_password.session_secret\"},\"Msg\":\"Open complete\"},\"type\":\"ephemeral_action_complete\"}",
                changes, jobDiagnostics);
        assertEquals("applied", changes.get(0).get("status"));

        subject().parseLine(
                "{\"@message\":\"ephemeral.random_password.session_secret: Closing...\",\"hook\":{\"resource\":{\"addr\":\"ephemeral.random_password.session_secret\"},\"Msg\":\"Closing...\"},\"type\":\"ephemeral_action_started\"}",
                changes, jobDiagnostics);
        assertEquals("ephemeral-closing", changes.get(0).get("status"));

        subject().parseLine(
                "{\"@message\":\"ephemeral.random_password.session_secret: Close complete\",\"hook\":{\"resource\":{\"addr\":\"ephemeral.random_password.session_secret\"},\"Msg\":\"Close complete\"},\"type\":\"ephemeral_action_complete\"}",
                changes, jobDiagnostics);
        assertEquals("applied", changes.get(0).get("status"));
        assertEquals(1, changes.size());
    }

    @Test
    void tracksEphemeralRenewUsingOpenTofusEventVocabulary() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"ephemeral.random_password.session_secret: Renewing...\",\"hook\":{\"resource\":{\"addr\":\"ephemeral.random_password.session_secret\"},\"Msg\":\"Renewing...\"},\"type\":\"ephemeral_action_started\"}",
                changes, jobDiagnostics);
        assertEquals("ephemeral-opening", changes.get(0).get("status"));

        subject().parseLine(
                "{\"@message\":\"ephemeral.random_password.session_secret: Renew complete\",\"hook\":{\"resource\":{\"addr\":\"ephemeral.random_password.session_secret\"},\"Msg\":\"Renew complete\"},\"type\":\"ephemeral_action_complete\"}",
                changes, jobDiagnostics);
        assertEquals("ephemeral-renewed", changes.get(0).get("status"));
    }

    @Test
    void ephemeralCloseErrorSetsEphemeralErroredStatus() {
        List<Map<String, Object>> changes = oneChange("random_password.ephemeral_secret");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"random_password.ephemeral_secret: Closing...\",\"hook\":{\"resource\":{\"addr\":\"random_password.ephemeral_secret\"},\"action\":\"close\"},\"type\":\"ephemeral_op_start\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"random_password.ephemeral_secret: Closing errored after 0s\",\"hook\":{\"resource\":{\"addr\":\"random_password.ephemeral_secret\"},\"action\":\"close\",\"elapsed_seconds\":0},\"type\":\"ephemeral_op_errored\"}",
                changes, jobDiagnostics);
        assertEquals("ephemeral-errored", changes.get(0).get("status"));
    }

    @Test
    void ephemeralOpStartSeedsANewEntryWhenNoneExists() {
        // Unlike a managed resource, an ephemeral resource never gets a planned_change event
        // (it's not part of the managed-resource diff) and is never seeded from the plan for
        // apply either, so ephemeral_op_start must seed its own row instead of relying on one
        // already being there - this reproduces the bug where ephemeral resources never
        // appeared in the structured output panel at all.
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"ephemeral.random_password.session_secret: Opening...\",\"hook\":{\"resource\":{\"addr\":\"ephemeral.random_password.session_secret\"},\"action\":\"open\"},\"type\":\"ephemeral_op_start\"}",
                changes, jobDiagnostics);

        assertEquals(1, changes.size());
        assertEquals("ephemeral.random_password.session_secret", changes.get(0).get("address"));
        assertEquals("ephemeral", changes.get(0).get("action"));
        assertEquals("ephemeral-opening", changes.get(0).get("status"));
    }

    @Test
    void ephemeralOpProgressUpdatesElapsedSecondsOnTheSeededEntry() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"random_password.ephemeral_secret: Still opening... [5s elapsed]\",\"hook\":{\"resource\":{\"addr\":\"random_password.ephemeral_secret\"},\"action\":\"open\",\"elapsed_seconds\":5},\"type\":\"ephemeral_op_start\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"random_password.ephemeral_secret: Still opening... [10s elapsed]\",\"hook\":{\"resource\":{\"addr\":\"random_password.ephemeral_secret\"},\"action\":\"open\",\"elapsed_seconds\":10},\"type\":\"ephemeral_op_progress\"}",
                changes, jobDiagnostics);

        assertEquals(1, changes.size());
        assertEquals(10, changes.get(0).get("elapsedSeconds"));
    }

    @Test
    void plannedChangeSeedsANewEntryWhenNoneExists() {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"aws_instance.new: Plan to create\",\"change\":{\"resource\":{\"addr\":\"aws_instance.new\"},\"action\":\"create\"},\"type\":\"planned_change\"}",
                changes, jobDiagnostics);

        assertEquals(1, changes.size());
        assertEquals("aws_instance.new", changes.get(0).get("address"));
        assertEquals("create", changes.get(0).get("action"));
        assertEquals("planned", changes.get(0).get("status"));
    }

    @Test
    void resourceDriftSetsDriftActionOnExistingEntry() {
        List<Map<String, Object>> changes = oneChange("aws_instance.drifted");
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"aws_instance.drifted: Drift detected (update)\",\"change\":{\"resource\":{\"addr\":\"aws_instance.drifted\"},\"action\":\"update\"},\"type\":\"resource_drift\"}",
                changes, jobDiagnostics);

        assertEquals("update", changes.get(0).get("driftAction"));
    }

    @Test
    void resourceDriftSeedsANewEntryWhenNoneExists() {
        // resource_drift fires during the pre-plan refresh, before planned_change has run for
        // that address - so (like ephemeral resources) there's no existing entry to update yet.
        // Reproduces the bug where drift never appeared in the structured output panel at all.
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"local_file.drift_demo: Drift detected (delete)\",\"change\":{\"resource\":{\"addr\":\"local_file.drift_demo\"},\"action\":\"delete\"},\"type\":\"resource_drift\"}",
                changes, jobDiagnostics);

        assertEquals(1, changes.size());
        assertEquals("local_file.drift_demo", changes.get(0).get("address"));
        assertEquals("delete", changes.get(0).get("driftAction"));
    }

    @Test
    void refreshStartAndCompleteSeedAnEntryWhenNoneExists() {
        // refresh_start/refresh_complete fire during the pre-plan refresh, before planned_change
        // has run for that address - reproduces the bug where the live "refreshing" status never
        // appeared for any resource during planning (every resource hits refresh before it's
        // seeded), even though the resource's final planned status still showed up correctly
        // once planned_change ran moments later.
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Refreshing state... [id=i-1]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"id_key\":\"id\",\"id_value\":\"i-1\"},\"type\":\"refresh_start\"}",
                changes, jobDiagnostics);

        assertEquals(1, changes.size());
        assertEquals("refreshing", changes.get(0).get("status"));

        subject().parseLine(
                "{\"@message\":\"aws_instance.foo: Refresh complete [id=i-1]\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.foo\"},\"id_key\":\"id\",\"id_value\":\"i-1\"},\"type\":\"refresh_complete\"}",
                changes, jobDiagnostics);

        assertEquals(1, changes.size());
        assertEquals("pending", changes.get(0).get("status"));
        assertNull(changes.get(0).get("previousStatus"));
    }

    @Test
    void refreshOnlyResourceDefaultsToNoOpAction() {
        // A resource that's only ever refreshed - never followed by planned_change/apply_start
        // because it's genuinely unchanged - previously ended up with no "action" at all (since
        // buildChangesFromPlanJson deliberately excludes no-op resources from the show-json diff
        // merge, to keep a large plan's full-state refresh from cluttering the list). The UI then
        // rendered it with an unlabeled "?" (unknown) badge instead of "no-op".
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"random_pet.unchanged: Refreshing state... [id=some-id]\",\"hook\":{\"resource\":{\"addr\":\"random_pet.unchanged\"},\"id_key\":\"id\",\"id_value\":\"some-id\"},\"type\":\"refresh_start\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"random_pet.unchanged: Refresh complete [id=some-id]\",\"hook\":{\"resource\":{\"addr\":\"random_pet.unchanged\"},\"id_key\":\"id\",\"id_value\":\"some-id\"},\"type\":\"refresh_complete\"}",
                changes, jobDiagnostics);

        assertEquals("no-op", changes.get(0).get("action"));
    }

    @Test
    void plannedChangeAfterRefreshOverridesTheNoOpDefault() {
        // A resource that IS actually changing gets refreshed first (implicit pre-plan pass) and
        // then a real planned_change - the real action must win over the no-op default the
        // refresh seeded.
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

        subject().parseLine(
                "{\"@message\":\"random_pet.changing: Refreshing state... [id=some-id]\",\"hook\":{\"resource\":{\"addr\":\"random_pet.changing\"},\"id_key\":\"id\",\"id_value\":\"some-id\"},\"type\":\"refresh_start\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"random_pet.changing: Refresh complete [id=some-id]\",\"hook\":{\"resource\":{\"addr\":\"random_pet.changing\"},\"id_key\":\"id\",\"id_value\":\"some-id\"},\"type\":\"refresh_complete\"}",
                changes, jobDiagnostics);
        subject().parseLine(
                "{\"@message\":\"random_pet.changing: Plan to update\",\"change\":{\"resource\":{\"addr\":\"random_pet.changing\"},\"action\":\"update\"},\"type\":\"planned_change\"}",
                changes, jobDiagnostics);

        assertEquals("update", changes.get(0).get("action"));
    }
}
