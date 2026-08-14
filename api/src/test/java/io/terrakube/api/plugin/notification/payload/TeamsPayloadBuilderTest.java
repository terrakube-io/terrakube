package io.terrakube.api.plugin.notification.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationMessageStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamsPayloadBuilderTest {

    private final TeamsPayloadBuilder builder = new TeamsPayloadBuilder(new ObjectMapper());

    private static final String WORKSPACE_URL = "https://terrakube.acme.com/organizations/acme/workspaces/networking";

    @Test
    void buildsAnAdaptiveCardWithTitleFactSetAndActionButton() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                WORKSPACE_URL + "/runs/42",
                "abc123", null, "Prod Alerts", WORKSPACE_URL, NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode card = root.get("attachments").get(0).get("content");
        JsonNode body = card.get("body");

        assertThat(card.get("type").asText()).isEqualTo("AdaptiveCard");
        assertThat(body.get(0).get("text").asText()).contains("Run notification for").contains(WORKSPACE_URL);
        assertThat(body.get(1).get("text").asText()).contains("networking").contains("completed");
        assertThat(root.toString()).contains("acme").contains("42").contains(context.runUrl()).contains("Prod Alerts");
    }

    @Test
    void includesCommitIdInTheFactSetWhenPresent() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                WORKSPACE_URL + "/runs/42",
                "abc123", null, "Prod Alerts", WORKSPACE_URL, NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);

        assertThat(payload).contains("Commit").contains("abc123");
    }

    @Test
    void omitsCommitFactWhenCommitIdIsAbsent() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                WORKSPACE_URL + "/runs/42",
                null, null, "Prod Alerts", WORKSPACE_URL, NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);

        assertThat(payload).doesNotContain("Commit");
    }

    @Test
    void includesFailureReasonWhenTheJobFailed() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 43, JobStatus.failed,
                WORKSPACE_URL + "/runs/43",
                null, "apply exited with code 1", "Prod Alerts", WORKSPACE_URL, NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);

        assertThat(payload).contains("apply exited with code 1");
    }

    @Test
    void omitsFailureReasonTextBlockWhenNotFailed() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                WORKSPACE_URL + "/runs/42",
                null, null, "Prod Alerts", WORKSPACE_URL, NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode body = root.get("attachments").get(0).get("content").get("body");

        // The workspace-link TextBlock, the title TextBlock, and the FactSet - no fourth,
        // failure-reason element.
        assertThat(body).hasSize(3);
    }

    @Test
    void omitsTheActionsListAndWorkspaceLinkWhenNeitherUrlIsPresent() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "(test notification)", 0, JobStatus.completed, null, null, null, "Test Notification", null,
                NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode card = root.get("attachments").get(0).get("content");

        assertThat(card.has("actions")).isFalse();
        assertThat(payload).doesNotContain("Run notification for");
    }

    @Test
    void simpleStyleGetsAMinimalOneBlockCardRegardlessOfStatus() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.running,
                WORKSPACE_URL + "/runs/42", "abc123", null, "Prod Alerts", WORKSPACE_URL,
                NotificationMessageStyle.SIMPLE);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode card = root.get("attachments").get(0).get("content");
        JsonNode body = card.get("body");

        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("text").asText()).contains("networking").contains("running");
        assertThat(card.has("actions")).isFalse();
        assertThat(payload).doesNotContain("FactSet").doesNotContain("Run notification for");
    }
}
