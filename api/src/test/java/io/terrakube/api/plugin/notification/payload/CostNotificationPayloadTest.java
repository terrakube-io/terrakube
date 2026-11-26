package io.terrakube.api.plugin.notification.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.cost.InfracostContextExtractor;
import io.terrakube.api.plugin.cost.InfracostSummary;
import io.terrakube.api.plugin.notification.JobFailureSummaryService;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.notification.NotificationConfigResolver;
import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationMessageStyle;
import io.terrakube.api.rs.notification.NotificationTrigger;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CostNotificationPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildersIncludeCostEstimationDetailsWithCamelCaseWebhookKeys() throws Exception {
        InfracostSummary costSummary = new InfracostSummary(
                "SUCCESS", "USD",
                new BigDecimal("165.20"), new BigDecimal("120.00"), new BigDecimal("45.20"),
                12, 10, 1, 1, "https://dashboard.infracost.io/share/demo", null);

        NotificationContext context = new NotificationContext(
                "simple-finops",
                "aws-cost-soft-fail",
                42,
                JobStatus.waitingApproval,
                "https://terrakube.localhost/runs/42",
                "abc123",
                null,
                "FinOps Alerts",
                "https://terrakube.localhost/workspaces/ws",
                NotificationMessageStyle.DETAILED,
                costSummary);

        String slackJson = new SlackPayloadBuilder(objectMapper).build(context);
        assertThat(slackJson)
                .contains("Pre-Deployment Cost Estimation")
                .contains("$165.20")
                .contains("+$45.20 (+37.7%)")
                .contains("2 unpriced resource(s)");

        String teamsJson = new TeamsPayloadBuilder(objectMapper).build(context);
        assertThat(teamsJson)
                .contains("Est. Monthly Cost")
                .contains("$165.20")
                .contains("Est. Monthly Delta")
                .contains("+$45.20 (+37.7%)");

        String webhookJson = new WebhookPayloadBuilder(objectMapper).build(context);
        JsonNode webhookNode = objectMapper.readTree(webhookJson);
        JsonNode costNode = webhookNode.path("costEstimation");
        assertThat(costNode.path("totalMonthlyCost").asText()).isEqualTo("165.20");
        assertThat(costNode.path("pastMonthlyCost").asText()).isEqualTo("120.00");
        assertThat(costNode.path("diffMonthlyCost").asText()).isEqualTo("45.20");
        assertThat(costNode.path("detectedResources").asInt()).isEqualTo(12);
        assertThat(costNode.path("supportedResources").asInt()).isEqualTo(10);
        assertThat(costNode.path("unsupportedResources").asInt()).isEqualTo(1);
        assertThat(costNode.path("noPriceResources").asInt()).isEqualTo(1);
        assertThat(costNode.path("shareUrl").asText()).isEqualTo("https://dashboard.infracost.io/share/demo");
        assertThat(costNode.path("estimated").asBoolean()).isTrue();
        assertThat(webhookNode.has("cost_estimation")).isFalse();
    }

    @Test
    void jobNotificationTriggerExtractsCostAtMostOnceAndSkipsSimpleStyles() {
        NotificationConfigResolver configResolver = mock(NotificationConfigResolver.class);
        NotificationPayloadRenderer renderer = mock(NotificationPayloadRenderer.class);
        NotificationOutboxRepository outboxRepository = mock(NotificationOutboxRepository.class);
        NotificationDispatchService dispatchService = mock(NotificationDispatchService.class);
        JobFailureSummaryService failureSummaryService = mock(JobFailureSummaryService.class);
        InfracostContextExtractor extractor = mock(InfracostContextExtractor.class);

        JobNotificationTrigger trigger = new JobNotificationTrigger(
                configResolver, renderer, outboxRepository, dispatchService, failureSummaryService, extractor);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("simple-finops");
        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setName("aws-cost-low");
        ws.setOrganization(org);

        Job job = new Job();
        job.setId(55);
        job.setStatus(JobStatus.completed);
        job.setOrganization(org);
        job.setWorkspace(ws);

        NotificationConfiguration simpleSlack = config("Simple Slack", NotificationChannelType.SLACK, NotificationMessageStyle.SIMPLE);
        when(configResolver.resolve(ws)).thenReturn(List.of(simpleSlack));
        when(renderer.render(eq(NotificationChannelType.SLACK), any())).thenReturn("{}");

        trigger.enqueue(job);
        verifyNoInteractions(extractor);
        clearInvocations(renderer);

        NotificationConfiguration detailedSlack = config("FinOps Slack", NotificationChannelType.SLACK, NotificationMessageStyle.DETAILED);
        NotificationConfiguration detailedTeams = config("FinOps Teams", NotificationChannelType.TEAMS, NotificationMessageStyle.DETAILED);
        NotificationConfiguration webhook = config("FinOps Webhook", NotificationChannelType.WEBHOOK, NotificationMessageStyle.DETAILED);

        InfracostSummary summary = new InfracostSummary(
                "SUCCESS", "USD",
                new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.00"),
                1, 1, 0, 0, null, null);

        when(configResolver.resolve(ws)).thenReturn(List.of(detailedSlack, detailedTeams, webhook));
        when(extractor.extract(55)).thenReturn(Optional.of(summary));
        when(renderer.render(any(), any())).thenReturn("{}");

        trigger.enqueue(job);

        verify(extractor, times(1)).extract(55);
        ArgumentCaptor<NotificationContext> contextCaptor = ArgumentCaptor.forClass(NotificationContext.class);
        verify(renderer, times(3)).render(any(), contextCaptor.capture());
        assertThat(contextCaptor.getAllValues()).extracting(NotificationContext::costSummary).containsOnly(summary);
    }

    private static NotificationConfiguration config(
            String name, NotificationChannelType channel, NotificationMessageStyle style) {
        NotificationConfiguration cfg = new NotificationConfiguration();
        cfg.setId(UUID.randomUUID());
        cfg.setName(name);
        cfg.setChannelType(channel);
        cfg.setMessageStyle(style);
        NotificationTrigger nt = new NotificationTrigger();
        nt.setJobStatus(JobStatus.completed);
        cfg.setTriggers(List.of(nt));
        return cfg;
    }
}
