package io.terrakube.api.plugin.notification.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationMessageStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadBuilderTest {

    private final WebhookPayloadBuilder builder = new WebhookPayloadBuilder(new ObjectMapper());

    @Test
    void buildsFlatJsonWithAllContextFields() throws Exception {
        NotificationContext context = new NotificationContext(
                "acme", "networking", 42, JobStatus.failed,
                "https://terrakube.acme.com/organizations/acme/workspaces/networking/runs/42",
                "abc123", "apply exited with code 1", "Prod Alerts",
                "https://terrakube.acme.com/organizations/acme/workspaces/networking", NotificationMessageStyle.DETAILED);

        String payload = builder.build(context);
        var root = new ObjectMapper().readTree(payload);

        assertThat(root.get("organization").asText()).isEqualTo("acme");
        assertThat(root.get("workspace").asText()).isEqualTo("networking");
        assertThat(root.get("jobId").asInt()).isEqualTo(42);
        assertThat(root.get("status").asText()).isEqualTo("failed");
        assertThat(root.get("runUrl").asText()).isEqualTo(context.runUrl());
        assertThat(root.get("commitId").asText()).isEqualTo("abc123");
        assertThat(root.get("failureReason").asText()).isEqualTo("apply exited with code 1");
        assertThat(root.get("configurationName").asText()).isEqualTo("Prod Alerts");
        assertThat(root.get("workspaceUrl").asText()).isEqualTo(context.workspaceUrl());
    }
}
