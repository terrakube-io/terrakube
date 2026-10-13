package io.terrakube.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.PrCommentService;
import io.terrakube.api.plugin.vcs.RepoWebhookDeliveryTransactions;
import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

// No ambient transaction: each worker saves and commits through the real repository proxy.
// Mock only provider I/O and scheduling, so JPA auditing is exercised before reloading the row.
class RepoWebhookJobAuditIntegrationTest extends ServerApplicationTests {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pull_request", "issue_comment"})
    void workerPersistsWebhookActor(String eventName) throws Exception {
        String actor = "issue_comment".equals(eventName) ? "comment-author" : "pr-author";
        assertWebhookActor(eventName, actor, actor);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingWebhookActorUsesInternal(String actor) throws Exception {
        assertWebhookActor("pull_request", actor, "Internal");
    }

    @Test
    void authenticatedSaveOverridesCallerSuppliedAuditFields() {
        Jwt jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none")
                .claim("email", "operator@example.com").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        Job job = completedJob();
        job.setCreatedBy("forged-author");
        job.setUpdatedBy("forged-author");
        int id = jobRepository.saveAndFlush(job).getId();
        try {
            Job saved = jobRepository.findById(id).orElseThrow();
            assertThat(saved.getCreatedBy()).isEqualTo("operator@example.com");
            assertThat(saved.getUpdatedBy()).isEqualTo("operator@example.com");
        } finally {
            jobRepository.deleteById(id);
        }
    }

    private Job completedJob() {
        Job job = new Job();
        job.setStatus(JobStatus.completed);
        job.setOrganization(organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow());
        job.setWorkspace(workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow());
        return job;
    }

    private void assertWebhookActor(String eventName, String actor, String expected) throws Exception {
        var gitHub = mock(GitHubWebhookService.class);
        var workspaceLookup = mock(WorkspaceRepository.class);
        var events = mock(WebhookEventRepository.class);
        var scheduling = mock(ScheduleJobService.class);

        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        // Use the fixture's persisted identities without carrying lazy proxies to the worker.
        workspace.setOrganization(organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow());
        workspace.setVcs(null);
        Webhook webhook = new Webhook();
        workspace.setWebhook(webhook);
        WebhookEvent event = new WebhookEvent();
        event.setEvent(WebhookEventType.PULL_REQUEST);
        event.setBranch(".*");
        event.setPath(".*");
        event.setTemplateId("audit-test-template");
        event.setPrWorkflowEnabled(true);
        when(events.findByWebhookAndEventOrderByPriorityAsc(webhook, WebhookEventType.PULL_REQUEST))
                .thenReturn(List.of(event));
        when(workspaceLookup.findByNormalizedSourceWithMigratedWebhook(any())).thenReturn(List.of(workspace));

        WebhookResult result = new WebhookResult();
        result.setEvent(eventName);
        result.setValid(true);
        result.setCreatedBy(actor);
        result.setBranch("feature");
        result.setCommit(UUID.randomUUID().toString());
        result.setPrNumber(42);
        result.setFileChanges(List.of("main.tf"));
        result.setPrComment("issue_comment".equals(eventName));
        result.setCommentCommand("plan");
        when(gitHub.parseGitHubPayload(eq("payload"), any())).thenReturn(result);
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setRepositoryUrl("https://github.com/example/audit-test");

        try (var worker = Executors.newSingleThreadExecutor()) {
            RepoWebhookService service = new RepoWebhookService(mock(RepoWebhookRepository.class), workspaceLookup,
                    events, gitHub, mock(GitLabWebhookService.class), mock(AzDevOpsWebhookService.class),
                    jobRepository, scheduling, mock(PrCommentService.class), mock(RepoWebhookDeliveryTransactions.class),
                    new ObjectMapper(), worker);
            service.processClaimedDelivery(repoWebhook, "payload", Map.of());
            var captor = org.mockito.ArgumentCaptor.forClass(Job.class);
            org.mockito.Mockito.verify(scheduling).createJobContext(captor.capture());
            int id = captor.getValue().getId();
            try {
                Job saved = jobRepository.findById(id).orElseThrow();
                assertThat(saved.getCreatedBy()).isEqualTo(expected);
                assertThat(saved.getUpdatedBy()).isEqualTo(expected);
                // Reuse the same worker after the scoped save; background jobs must not inherit its actor.
                int backgroundId = worker.submit(() -> {
                    Job background = completedJob();
                    return jobRepository.saveAndFlush(background).getId();
                }).get();
                try {
                    Job background = jobRepository.findById(backgroundId).orElseThrow();
                    assertThat(background.getCreatedBy()).isEqualTo("Internal");
                    assertThat(background.getUpdatedBy()).isEqualTo("Internal");
                } finally {
                    jobRepository.deleteById(backgroundId);
                }
            } finally {
                jobRepository.deleteById(id);
            }
        }
    }
}
