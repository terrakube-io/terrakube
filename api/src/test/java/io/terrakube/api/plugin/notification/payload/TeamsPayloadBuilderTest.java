package io.terrakube.api.plugin.notification.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamsPayloadBuilderTest {

    private final TeamsPayloadBuilder builder = new TeamsPayloadBuilder(new ObjectMapper());

    @Test
    void buildsAnAdaptiveCardWithTitleFactSetAndActionButton() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/42",
                "abc123", null);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode card = root.get("attachments").get(0).get("content");
        JsonNode body = card.get("body");

        assertThat(card.get("type").asText()).isEqualTo("AdaptiveCard");
        assertThat(body.get(0).get("text").asText()).contains("networking").contains("completed");
        assertThat(root.toString()).contains("acme").contains("42").contains(context.runUrl());
    }

    @Test
    void includesCommitIdInTheFactSetWhenPresent() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/42",
                "abc123", null);

        String payload = builder.build(context);

        assertThat(payload).contains("Commit").contains("abc123");
    }

    @Test
    void omitsCommitFactWhenCommitIdIsAbsent() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/42",
                null, null);

        String payload = builder.build(context);

        assertThat(payload).doesNotContain("Commit");
    }

    @Test
    void includesFailureReasonWhenTheJobFailed() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 43, JobStatus.failed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/43",
                null, "apply exited with code 1");

        String payload = builder.build(context);

        assertThat(payload).contains("apply exited with code 1");
    }

    @Test
    void omitsFailureReasonTextBlockWhenNotFailed() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/42",
                null, null);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode body = root.get("attachments").get(0).get("content").get("body");

        // Just the title TextBlock and the FactSet - no third, failure-reason element.
        assertThat(body).hasSize(2);
    }
}
