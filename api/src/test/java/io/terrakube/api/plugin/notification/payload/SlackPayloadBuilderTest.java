package io.terrakube.api.plugin.notification.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationMessageStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackPayloadBuilderTest {

    private final SlackPayloadBuilder builder = new SlackPayloadBuilder(new ObjectMapper());

    private static final String WORKSPACE_URL = "https://terrakube.acme.com/organizations/acme/workspaces/networking";

    @Test
    void buildsBlockKitWithHeaderContextAndActionBlocks() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                WORKSPACE_URL + "/runs/42",
                "abc123", null, "Prod Alerts", WORKSPACE_URL, NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode attachment = root.get("attachments").get(0);
        JsonNode blocks = attachment.get("blocks");

        // The top-level "text" carries the "Run notification for org/workspace" link, not a
        // repeat of the header - Slack always shows this as a separate line once blocks live
        // inside "attachments", so it doubles as the preview/push-notification text and must not
        // just duplicate what's already in the card.
        assertThat(root.get("text").asText())
                .contains("Run notification for").contains(WORKSPACE_URL).contains("acme/networking");
        assertThat(root.get("username").asText()).isEqualTo("networking");
        assertThat(attachment.get("color").asText()).isEqualTo("#2eb67d");
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("header");
        assertThat(blocks.get(0).get("text").get("text").asText()).contains("networking").contains("Completed");
        assertThat(root.toString()).contains("acme").contains("42").contains(context.runUrl()).contains("Prod Alerts");
    }

    @Test
    void includesFailureReasonWhenPresent() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 43, JobStatus.failed,
                WORKSPACE_URL + "/runs/43",
                null, "apply exited with code 1", "Prod Alerts", WORKSPACE_URL, NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);
        assertThat(payload).contains("apply exited with code 1");
    }

    @Test
    void omitsTheViewRunButtonAndWorkspaceLinkWhenNeitherUrlIsPresent() throws Exception {
        // Test sends (NotificationTestService) have no real run/workspace page to link to -
        // Slack's Block Kit rejects a button whose url isn't a valid absolute URL, so a
        // placeholder like "#" would fail delivery entirely rather than just render a dead link.
        NotificationContext context = new NotificationContext(
                "acme", "(test notification)", 0, JobStatus.completed, null, null, null, "Test Notification", null,
                NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode blocks = root.get("attachments").get(0).get("blocks");

        assertThat(blocks).noneMatch(block -> "actions".equals(block.get("type").asText()));
        assertThat(payload).doesNotContain("Run notification for");
    }

    @Test
    void simpleStyleGetsACompactSingleLinePayloadRegardlessOfStatus() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.running,
                WORKSPACE_URL + "/runs/42", "abc123", null, "Prod Alerts", WORKSPACE_URL,
                NotificationMessageStyle.SIMPLE);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);

        assertThat(root.get("text").asText()).contains("networking").contains("Running");
        assertThat(root.get("username").asText()).isEqualTo("networking");
        assertThat(root.has("attachments")).isFalse();
    }

    @Test
    void detailedStyleGetsTheFullCardRegardlessOfStatus() throws Exception {
        for (JobStatus status : new JobStatus[] { JobStatus.queue, JobStatus.running, JobStatus.completed,
                JobStatus.failed }) {
            NotificationContext context = new NotificationContext(
                    "acme", "networking", 42, status,
                    WORKSPACE_URL + "/runs/42", "abc123", null, "Prod Alerts", WORKSPACE_URL,
                    NotificationMessageStyle.DETAILED);

            String payload = builder.build(context);
            JsonNode root = new ObjectMapper().readTree(payload);

            assertThat(root.has("attachments")).as("status %s should get the full card", status).isTrue();
        }
    }
}
