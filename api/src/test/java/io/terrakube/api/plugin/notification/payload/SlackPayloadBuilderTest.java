package io.terrakube.api.plugin.notification.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackPayloadBuilderTest {

    private final SlackPayloadBuilder builder = new SlackPayloadBuilder(new ObjectMapper());

    @Test
    void buildsBlockKitWithHeaderContextAndActionBlocks() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.completed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/42",
                "abc123", null);

        String payload = builder.build(context);
        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode attachment = root.get("attachments").get(0);
        JsonNode blocks = attachment.get("blocks");

        assertThat(attachment.get("color").asText()).isEqualTo("#2eb67d");
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("header");
        assertThat(blocks.get(0).get("text").get("text").asText()).contains("networking").contains("Completed");
        assertThat(root.toString()).contains("acme").contains("42").contains(context.runUrl());
    }

    @Test
    void includesFailureReasonWhenPresent() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 43, JobStatus.failed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/43",
                null, "apply exited with code 1");

        String payload = builder.build(context);
        assertThat(payload).contains("apply exited with code 1");
    }
}
