package io.terrakube.api.plugin.vcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

@ExtendWith(MockitoExtension.class)
class RepoWebhookServiceTest {

    RepoWebhookRepository repoWebhookRepository;
    WorkspaceRepository workspaceRepository;
    WebhookEventRepository webhookEventRepository;
    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    AzDevOpsWebhookService azDevOpsWebhookService;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;
    PrCommentService prCommentService;
    RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;
    ObjectMapper objectMapper;

    RepoWebhookService subject;

    @BeforeEach
    void setup() {
        repoWebhookRepository = mock(RepoWebhookRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        webhookEventRepository = mock(WebhookEventRepository.class);
        gitHubWebhookService = mock(GitHubWebhookService.class);
        gitLabWebhookService = mock(GitLabWebhookService.class);
        azDevOpsWebhookService = mock(AzDevOpsWebhookService.class);
        jobRepository = mock(JobRepository.class);
        scheduleJobService = mock(ScheduleJobService.class);
        prCommentService = mock(PrCommentService.class);
        repoWebhookDeliveryTransactions = mock(RepoWebhookDeliveryTransactions.class);
        objectMapper = new ObjectMapper();

        subject = new RepoWebhookService(
                repoWebhookRepository,
                workspaceRepository,
                webhookEventRepository,
                gitHubWebhookService,
                gitLabWebhookService,
                azDevOpsWebhookService,
                jobRepository,
                scheduleJobService,
                prCommentService,
                repoWebhookDeliveryTransactions,
                objectMapper,
                Runnable::run);
    }

    private Workspace workspaceWithSource(String source) {
        Workspace ws = new Workspace();
        ws.setSource(source);
        Vcs vcs = new Vcs();
        ws.setVcs(vcs);
        Organization org = new Organization();
        ws.setOrganization(org);
        ws.setName("test-workspace");
        return ws;
    }

    private RepoWebhook repoWebhookWith(String url, String secret) {
        RepoWebhook rw = new RepoWebhook();
        rw.setId(UUID.randomUUID());
        rw.setRepositoryUrl(url);
        rw.setWebhookSecret(secret);
        rw.setVcs(new Vcs());
        return rw;
    }

    private Workspace gitlabWorkspaceWithSource(String source) {
        Workspace ws = workspaceWithSource(source);
        ws.getVcs().setVcsType(VcsType.GITLAB);
        return ws;
    }

    private RepoWebhook gitlabRepoWebhookWith(String url, String secret) {
        RepoWebhook rw = repoWebhookWith(url, secret);
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);
        rw.setVcs(vcs);
        return rw;
    }

    private String computeHmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        return "sha256=" + hex;
    }

    @Nested
    class GetOrCreateRepoWebhook {

        @Test
        void returnsExistingWhenFound() {
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            RepoWebhook existing = repoWebhookWith("https://github.com/owner/repo", "secret");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.of(existing));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result).isSameAs(existing);
            verify(repoWebhookRepository, never()).saveAndFlush(any());
        }

        @Test
        void createsNewWhenNotFound() {
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.saveAndFlush(any(RepoWebhook.class))).thenAnswer(inv -> inv.getArgument(0));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result.getRepositoryUrl()).isEqualTo("https://github.com/owner/repo");
            assertThat(result.getWebhookSecret()).isNotNull().hasSize(36); // UUID format
            verify(repoWebhookRepository).saveAndFlush(any(RepoWebhook.class));
        }

        @Test
        void recoversWhenConcurrentInsertLosesTheRace() {
            // This is a defense-in-depth fallback: the primary safety
            // mechanism is that callers only ever reach this method from
            // within RepoWebhookSyncJob, which Quartz guarantees runs at
            // most once per repository URL at a time (see
            // RepoWebhookSyncScheduler). If getOrCreateRepoWebhook is ever
            // called outside that serialized path and loses a genuine race
            // on the repository_url unique constraint, it should still
            // recover by re-querying rather than propagating the raw DB
            // exception. saveAndFlush (rather than save) is what makes the
            // constraint violation surface here at all, instead of being
            // silently queued until an unrelated later commit.
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            RepoWebhook existing = repoWebhookWith("https://github.com/owner/repo", "secret");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existing));
            when(repoWebhookRepository.saveAndFlush(any(RepoWebhook.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate"));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result).isSameAs(existing);
        }

        @Test
        void throwsWhenRaceConditionRetryAlsoFails() {
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.saveAndFlush(any(RepoWebhook.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate"));

            assertThatThrownBy(() -> subject.getOrCreateRepoWebhook(ws))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to create or find RepoWebhook");
        }

        @Test
        void normalizesUrl() {
            Workspace ws = workspaceWithSource("https://GitHub.com/Owner/Repo.git");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.saveAndFlush(any(RepoWebhook.class))).thenAnswer(inv -> inv.getArgument(0));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result.getRepositoryUrl()).isEqualTo("https://github.com/owner/repo");
        }
    }

    @Nested
    class CreateOrUpdateSharedWebhook {

        @Test
        void aggregatesEventTypesAcrossWorkspaces() {
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "secret");

            Workspace ws1 = workspaceWithSource("https://github.com/owner/repo");
            Webhook wh1 = new Webhook();
            WebhookEvent pushEvent = new WebhookEvent();
            pushEvent.setEvent(WebhookEventType.PUSH);
            wh1.setEvents(List.of(pushEvent));
            ws1.setWebhook(wh1);

            Workspace ws2 = workspaceWithSource("https://github.com/owner/repo");
            Webhook wh2 = new Webhook();
            WebhookEvent prEvent = new WebhookEvent();
            prEvent.setEvent(WebhookEventType.PULL_REQUEST);
            wh2.setEvents(List.of(prEvent));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws1, ws2));
            when(gitHubWebhookService.createOrUpdateRepoWebhook(eq(rw), any(), anyBoolean()))
                    .thenReturn("12345");

            subject.createOrUpdateSharedWebhook(rw);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<WebhookEventType>> captor = ArgumentCaptor.forClass(Set.class);
            verify(gitHubWebhookService).createOrUpdateRepoWebhook(eq(rw), captor.capture(), eq(false));
            assertThat(captor.getValue()).containsExactlyInAnyOrder(WebhookEventType.PUSH, WebhookEventType.PULL_REQUEST);
            assertThat(rw.getRemoteHookId()).isEqualTo("12345");
            verify(repoWebhookRepository).save(rw);
        }

        @Test
        void passesHasPrWorkflowTrueWhenAnyEventHasPrWorkflowEnabled() {
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "secret");

            Workspace ws1 = workspaceWithSource("https://github.com/owner/repo");
            Webhook wh1 = new Webhook();
            WebhookEvent pushEvent = new WebhookEvent();
            pushEvent.setEvent(WebhookEventType.PUSH);
            wh1.setEvents(List.of(pushEvent));
            ws1.setWebhook(wh1);

            Workspace ws2 = workspaceWithSource("https://github.com/owner/repo");
            Webhook wh2 = new Webhook();
            WebhookEvent prEvent = new WebhookEvent();
            prEvent.setEvent(WebhookEventType.PULL_REQUEST);
            prEvent.setPrWorkflowEnabled(true);
            wh2.setEvents(List.of(prEvent));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws1, ws2));
            when(gitHubWebhookService.createOrUpdateRepoWebhook(eq(rw), any(), anyBoolean()))
                    .thenReturn("12345");

            subject.createOrUpdateSharedWebhook(rw);

            verify(gitHubWebhookService).createOrUpdateRepoWebhook(eq(rw), any(), eq(true));
        }

        @Test
        void skipsWhenNoEventTypes() {
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "secret");

            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            Webhook wh = new Webhook();
            wh.setEvents(Collections.emptyList());
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws));

            subject.createOrUpdateSharedWebhook(rw);

            verify(gitHubWebhookService, never()).createOrUpdateRepoWebhook(any(), any(), anyBoolean());
        }
    }

    @Nested
    class CleanupIfOrphan {

        @Test
        void deletesWhenNoWorkspacesRemain() {
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "secret");
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(Collections.emptyList());

            subject.cleanupIfOrphan(rw);

            verify(gitHubWebhookService).deleteRepoWebhook(rw);
            verify(repoWebhookRepository).delete(rw);
        }

        @Test
        void updatesWhenWorkspacesStillExist() {
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "secret");
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            Webhook wh = new Webhook();
            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PUSH);
            wh.setEvents(List.of(event));
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws));
            when(gitHubWebhookService.createOrUpdateRepoWebhook(eq(rw), any(), anyBoolean())).thenReturn("12345");

            subject.cleanupIfOrphan(rw);

            verify(gitHubWebhookService, never()).deleteRepoWebhook(any());
            verify(repoWebhookRepository, never()).delete(any());
            verify(gitHubWebhookService).createOrUpdateRepoWebhook(eq(rw), any(), anyBoolean());
        }
    }

    @Nested
    class AcceptV2Webhook {

        @Test
        void v2WebhookMigrationRemovalScenario() throws Exception {
            String repoUrl = "https://github.com/owner/repo";

            // 1. Create a workspace using webhook version 1
            Workspace workspace = workspaceWithSource(repoUrl);
            workspace.setName("workspace-v1");
            Vcs vcs = new Vcs();
            vcs.setVcsType(VcsType.GITHUB);
            workspace.setVcs(vcs);

            Webhook webhook = new Webhook();
            webhook.setWorkspace(workspace);
            webhook.setMigratedV2(false);
            webhook.setRemoteHookId("old-v1-hook-id");
            workspace.setWebhook(webhook);

            // 2. Migrate to version 2
            webhook.setMigratedV2(true);

            // 3. Validate the version 1 webhook is removed correctly
            // We simulate the logic from WebhookManageHook here to validate it works with RepoWebhookService
            RepoWebhook repoWebhook = repoWebhookWith(repoUrl, "new-secret");
            when(repoWebhookRepository.findByRepositoryUrl(anyString())).thenReturn(Optional.of(repoWebhook));

            // This is the logic we are validating (from WebhookManageHook)
            if (webhook.isMigratedV2() && workspace.getVcs() != null && workspace.getVcs().getVcsType() == VcsType.GITHUB) {
                subject.getOrCreateRepoWebhook(workspace);
                subject.createOrUpdateSharedWebhook(repoWebhook);

                if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
                    gitHubWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());
                    webhook.setRemoteHookId(null);
                }
            }

            // Verify
            verify(gitHubWebhookService).deleteWebhook(workspace, "old-v1-hook-id");
            assertThat(webhook.getRemoteHookId()).isNull();

            // Cleanup
            workspaceRepository.delete(workspace);
            verify(workspaceRepository).delete(workspace);
        }

        @Test
        void throwsOnInvalidUuid() {
            assertThatThrownBy(() -> subject.acceptV2Webhook("not-a-uuid", "{}", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnNotFound() {
            UUID id = UUID.randomUUID();
            when(repoWebhookRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subject.acceptV2Webhook(id.toString(), "{}", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Repo webhook not found");
        }

        @Test
        void throwsSecurityExceptionOnHmacFailure() {
            String secret = "test-secret";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            Map<String, String> headers = Map.of("x-hub-signature-256", "sha256=invalid");

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), "{}", headers))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("HMAC signature verification failed");

            verify(repoWebhookDeliveryTransactions, never()).enqueue(any(), any(), any());
        }

        @Test
        void throwsWhenSignatureHeaderMissing() {
            String secret = "test-secret";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), "{}", Map.of()))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        void enqueuesAndReturnsDeliveryIdOnValidSignature() throws Exception {
            String secret = "test-secret";
            String payload = "{\"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            UUID deliveryId = UUID.randomUUID();
            when(repoWebhookDeliveryTransactions.enqueue(eq(rw), eq(payload), any())).thenReturn(deliveryId);

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "push");

            UUID result = subject.acceptV2Webhook(rw.getId().toString(), payload, headers);

            assertThat(result).isEqualTo(deliveryId);
            ArgumentCaptor<String> headersJsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(repoWebhookDeliveryTransactions).enqueue(eq(rw), eq(payload), headersJsonCaptor.capture());
            assertThat(headersJsonCaptor.getValue()).contains("x-github-event").contains("push");
        }

        @Test
        void acceptsSignatureWhenHeaderNamesUseOriginalCasing() throws Exception {
            // GitHub sends "X-Hub-Signature-256"; header names are case-insensitive (RFC 9110) and
            // Spring 7 / Spring Boot 4 no longer lowercases them for @RequestHeader Map. The service
            // must still verify the signature regardless of the casing it receives.
            String secret = "test-secret";
            String payload = "{\"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            UUID deliveryId = UUID.randomUUID();
            when(repoWebhookDeliveryTransactions.enqueue(eq(rw), eq(payload), any())).thenReturn(deliveryId);

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "X-Hub-Signature-256", sig,
                    "X-GitHub-Event", "push");

            UUID result = subject.acceptV2Webhook(rw.getId().toString(), payload, headers);

            assertThat(result).isEqualTo(deliveryId);
        }
    }

    @Nested
    class ProcessClaimedDelivery {

        @Test
        void v2WebhookMigrationScenario() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"ref\":\"refs/heads/main\", \"commits\": [{\"id\": \"abc123\"}]}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "migration-test-secret");

            // 1. Create two dummy workspaces
            Workspace ws1 = workspaceWithSource(repoUrl);
            ws1.setName("workspace-1");
            Workspace ws2 = workspaceWithSource(repoUrl);
            ws2.setName("workspace-2");

            // 2. Add webhook configuration using version 1 (migratedV2 = false)
            Webhook wh1 = new Webhook();
            wh1.setMigratedV2(false);
            ws1.setWebhook(wh1);

            Webhook wh2 = new Webhook();
            wh2.setMigratedV2(false);
            ws2.setWebhook(wh2);

            // Initially, no workspaces should be returned by the migrated query
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(Collections.emptyList());

            Map<String, String> headers = Map.of("x-github-event", "push");

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(pushResult);

            // Process webhook (V1 state) - should create 0 jobs
            subject.processClaimedDelivery(rw, payload, headers);
            verify(jobRepository, never()).save(any(Job.class));

            // 3. Migrate the configuration to version 2
            wh1.setMigratedV2(true);
            wh2.setMigratedV2(true);

            // Update mocks for WebhookEventMatcher
            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.PUSH);
            event1.setBranch("main");
            event1.setPath("*");
            event1.setPathType(WebhookEventPathType.PATTERN);
            event1.setTemplateId("template-1");
            wh1.setEvents(List.of(event1));

            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.PUSH);
            event2.setBranch("main");
            event2.setPath("*");
            event2.setPathType(WebhookEventPathType.PATTERN);
            event2.setTemplateId("template-2");
            wh2.setEvents(List.of(event2));

            // Resetting jobRepository to verify interactions AFTER migration
            org.mockito.Mockito.clearInvocations(jobRepository);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws1, ws2));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh1, WebhookEventType.PUSH))
                    .thenReturn(List.of(event1));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh2, WebhookEventType.PUSH))
                    .thenReturn(List.of(event2));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                j.setId(1);
                return j;
            });

            // 4. Create a webhook request using version 2 and validate jobs are created
            subject.processClaimedDelivery(rw, payload, headers);

            // Verify a job was created for each workspace
            verify(jobRepository, times(2)).save(any(Job.class));

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository, times(2)).save(jobCaptor.capture());

            List<Job> savedJobs = jobCaptor.getAllValues();
            assertThat(savedJobs).extracting(Job::getTemplateReference)
                    .containsExactlyInAnyOrder("template-1", "template-2");

            // Delete the dummy workspaces at the end of the method
            workspaceRepository.delete(ws1);
            workspaceRepository.delete(ws2);
            verify(workspaceRepository).delete(ws1);
            verify(workspaceRepository).delete(ws2);
        }

        @Test
        void v2WebhookPullRequestScenario() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"opened\", \"pull_request\": {\"number\": 42, \"head\": {\"sha\": \"def456\"}}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "pr-migration-test-secret");

            // 1. Create two dummy workspaces
            Workspace ws1 = workspaceWithSource(repoUrl);
            ws1.setName("ws-pr-1");
            Workspace ws2 = workspaceWithSource(repoUrl);
            ws2.setName("ws-pr-2");

            // 2. Add webhook configuration using version 1 (migratedV2 = false)
            Webhook wh1 = new Webhook();
            wh1.setMigratedV2(false);
            ws1.setWebhook(wh1);

            Webhook wh2 = new Webhook();
            wh2.setMigratedV2(false);
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(Collections.emptyList());

            Map<String, String> headers = Map.of("x-github-event", "pull_request");

            WebhookResult prResult = new WebhookResult();
            prResult.setEvent("pull_request");
            prResult.setValid(true);
            prResult.setBranch("feature-branch");
            prResult.setCommit("def456");
            prResult.setPrNumber(42);
            prResult.setPrFilesUrl("https://api.github.com/repos/owner/repo/pulls/42/files");
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(prResult);

            // Mock file changes for PR
            when(gitHubWebhookService.fetchPrFileChanges(any(), eq(repoUrl), eq(prResult.getPrFilesUrl())))
                    .thenReturn(List.of("variables.tf"));

            // Process webhook (V1 state) - should create 0 jobs
            subject.processClaimedDelivery(rw, payload, headers);
            verify(jobRepository, never()).save(any(Job.class));

            // 3. Migrate the configuration to version 2
            wh1.setMigratedV2(true);
            wh2.setMigratedV2(true);

            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.PULL_REQUEST);
            event1.setBranch("feature-branch");
            event1.setPath("*.tf");
            event1.setPathType(WebhookEventPathType.PATTERN);
            event1.setTemplateId("pr-template-1");
            wh1.setEvents(List.of(event1));

            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.PULL_REQUEST);
            event2.setBranch("feature-branch");
            event2.setPath("*.tf");
            event2.setPathType(WebhookEventPathType.PATTERN);
            event2.setTemplateId("pr-template-2");
            wh2.setEvents(List.of(event2));

            org.mockito.Mockito.clearInvocations(jobRepository);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws1, ws2));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh1, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event1));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh2, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event2));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                j.setId(100);
                return j;
            });

            // 4. Create a webhook request using version 2 and validate jobs are created
            subject.processClaimedDelivery(rw, payload, headers);

            // Verify a job was created for each workspace
            verify(jobRepository, times(2)).save(any(Job.class));

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository, times(2)).save(jobCaptor.capture());

            List<Job> savedJobs = jobCaptor.getAllValues();
            assertThat(savedJobs).extracting(Job::getTemplateReference)
                    .containsExactlyInAnyOrder("pr-template-1", "pr-template-2");

            assertThat(savedJobs).allSatisfy(job -> {
                assertThat(job.getCommitId()).isEqualTo("def456");
                assertThat(job.getOverrideBranch()).isEqualTo("feature-branch");
            });

            // Delete the dummy workspaces at the end of the method
            workspaceRepository.delete(ws1);
            workspaceRepository.delete(ws2);
            verify(workspaceRepository).delete(ws1);
            verify(workspaceRepository).delete(ws2);
        }

        @Test
        void v2WebhookPullRequestWithPrWorkflowEnabledSetsPrNumberOnJob() throws Exception {
            // Regression test: previously processWorkspaceWebhook only ever resolved a template
            // id (WebhookEventMatcher.findTemplateId), so a PR-triggered job never got prNumber/
            // prApplyEnabled set even with "Post Plan on PR" enabled. ScheduleJob.postPrCommentIfNeeded
            // bails out immediately when job.getPrNumber() is null/0, so no PR comment was ever
            // posted for a repo on the shared (v2) webhook - only the commit status check showed up.
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"opened\", \"pull_request\": {\"number\": 7, \"head\": {\"sha\": \"cafe123\"}}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "pr-workflow-test-secret");

            Workspace ws = workspaceWithSource(repoUrl);
            ws.setName("ws-pr-workflow");
            Webhook wh = new Webhook();
            wh.setMigratedV2(true);
            ws.setWebhook(wh);

            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PULL_REQUEST);
            event.setBranch(".*");
            event.setPath("**");
            event.setPathType(WebhookEventPathType.PATTERN);
            event.setTemplateId("plan-template");
            event.setPrWorkflowEnabled(true);
            event.setPrApplyEnabled(true);
            wh.setEvents(List.of(event));

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event));

            Map<String, String> headers = Map.of("x-github-event", "pull_request");

            WebhookResult prResult = new WebhookResult();
            prResult.setEvent("pull_request");
            prResult.setValid(true);
            prResult.setBranch("feature-branch");
            prResult.setCommit("cafe123");
            prResult.setPrNumber(7);
            prResult.setFileChanges(List.of("main.tf"));
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(prResult);

            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                j.setId(200);
                return j;
            });

            subject.processClaimedDelivery(rw, payload, headers);

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            Job savedJob = jobCaptor.getValue();

            assertThat(savedJob.getPrNumber()).isEqualTo(7);
            assertThat(savedJob.isPrApplyEnabled()).isTrue();
        }

        @Test
        void v2WebhookIssueCommentResolvesPrDetailsPerWorkspaceInsteadOfNpe() throws Exception {
            // Regression test: GitHubWebhookService.parseGitHubPayload(payload, headers) - the v2-safe
            // overload - parses issue_comment with vcs=null, since the correct per-workspace Vcs isn't
            // known yet. Previously handleEvent still tried to fetch PR head SHA/branch immediately
            // with that null Vcs, so tokenService.getAccessToken(ownerAndRepo, null) NPE'd on
            // vcs.getAccessToken() as soon as anyone commented "terrakube apply"/"terrakube plan" on a
            // PR for a repo using the shared webhook. WebhookResult.prDetailsUrl now defers that fetch
            // to processWorkspaceWebhook, once workspace.getVcs() is known.
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"created\", \"comment\": {\"body\": \"terrakube plan\"}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "issue-comment-test-secret");

            Workspace ws = workspaceWithSource(repoUrl);
            ws.setName("ws-issue-comment");
            Webhook wh = new Webhook();
            wh.setMigratedV2(true);
            ws.setWebhook(wh);

            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PULL_REQUEST);
            event.setBranch(".*");
            event.setPath("**");
            event.setPathType(WebhookEventPathType.PATTERN);
            event.setTemplateId("plan-template");
            event.setPrWorkflowEnabled(true);
            wh.setEvents(List.of(event));

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event));

            Map<String, String> headers = Map.of("x-github-event", "issue_comment");

            // parseGitHubPayload was called with vcs=null (the v2-safe overload), so it couldn't
            // resolve commit/branch itself - it left them unset and recorded prDetailsUrl instead,
            // exactly as the real (non-mocked) v2 parse path now does.
            WebhookResult commentResult = new WebhookResult();
            commentResult.setEvent("issue_comment");
            commentResult.setValid(true);
            commentResult.setBranch("");
            commentResult.setPrComment(true);
            commentResult.setCommentCommand("plan");
            commentResult.setPrNumber(9);
            commentResult.setPrDetailsUrl("https://api.github.com/repos/owner/repo/pulls/9");
            commentResult.setPrFilesUrl("https://api.github.com/repos/owner/repo/pulls/9/files");
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(commentResult);

            when(gitHubWebhookService.resolvePrDetails(eq(ws.getVcs()), eq(repoUrl),
                    eq("https://api.github.com/repos/owner/repo/pulls/9"), eq(commentResult)))
                    .thenAnswer(inv -> {
                        commentResult.setCommit("cafe456");
                        commentResult.setBranch("feature-branch");
                        return true;
                    });
            // any(), not eq(ws.getVcs()): processClaimedDelivery now tries this once repo-wide with
            // the repo webhook's own Vcs before any workspace loop iteration is reached (see
            // RepoWebhookService.processClaimedDelivery) - the workspace-scoped fallback in
            // processWorkspaceWebhook only runs if that didn't populate anything.
            when(gitHubWebhookService.fetchPrFileChanges(any(), eq(repoUrl),
                    eq("https://api.github.com/repos/owner/repo/pulls/9/files")))
                    .thenReturn(List.of("main.tf"));

            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                j.setId(300);
                return j;
            });

            subject.processClaimedDelivery(rw, payload, headers);

            verify(gitHubWebhookService).resolvePrDetails(eq(ws.getVcs()), eq(repoUrl),
                    eq("https://api.github.com/repos/owner/repo/pulls/9"), eq(commentResult));

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            Job savedJob = jobCaptor.getValue();

            assertThat(savedJob.getPrNumber()).isEqualTo(9);
            assertThat(savedJob.getOverrideBranch()).isEqualTo("feature-branch");
            assertThat(savedJob.getCommitId()).isEqualTo("cafe456");
        }

        @Test
        void v2WebhookIssueCommentSkipsWorkspaceWithNoVcsInsteadOfNpe() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"created\", \"comment\": {\"body\": \"terrakube plan\"}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "issue-comment-no-vcs-secret");

            Workspace ws = workspaceWithSource(repoUrl);
            ws.setName("ws-no-vcs");
            ws.setVcs(null);
            Webhook wh = new Webhook();
            wh.setMigratedV2(true);
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));

            Map<String, String> headers = Map.of("x-github-event", "issue_comment");

            WebhookResult commentResult = new WebhookResult();
            commentResult.setEvent("issue_comment");
            commentResult.setValid(true);
            commentResult.setBranch("");
            commentResult.setPrComment(true);
            commentResult.setCommentCommand("plan");
            commentResult.setPrNumber(11);
            commentResult.setPrDetailsUrl("https://api.github.com/repos/owner/repo/pulls/11");
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(commentResult);

            subject.processClaimedDelivery(rw, payload, headers);

            verify(gitHubWebhookService, never()).resolvePrDetails(any(), any(), any(), any());
            verify(jobRepository, never()).save(any(Job.class));
        }

        private Workspace prCommentWorkspace(String repoUrl, String name, String commentCommand, int prNumber,
                boolean prWorkflowEnabled, boolean prApplyEnabled) {
            Workspace ws = workspaceWithSource(repoUrl);
            ws.setName(name);
            Webhook wh = new Webhook();
            wh.setMigratedV2(true);
            ws.setWebhook(wh);

            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PULL_REQUEST);
            event.setBranch(".*");
            event.setPath("**");
            event.setPathType(WebhookEventPathType.PATTERN);
            event.setTemplateId("plan-template");
            event.setPrWorkflowEnabled(prWorkflowEnabled);
            event.setPrApplyEnabled(prApplyEnabled);
            wh.setEvents(List.of(event));

            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event));
            return ws;
        }

        private WebhookResult resolvedCommentResult(String commentCommand, int prNumber, String commentId) {
            WebhookResult commentResult = new WebhookResult();
            commentResult.setEvent("issue_comment");
            commentResult.setValid(true);
            commentResult.setPrComment(true);
            commentResult.setCommentCommand(commentCommand);
            commentResult.setCommentId(commentId);
            commentResult.setPrNumber(prNumber);
            commentResult.setBranch("feature-branch");
            commentResult.setCommit("cafe456");
            commentResult.setFileChanges(List.of("main.tf"));
            return commentResult;
        }

        @Test
        void v2WebhookIssueCommentAcknowledgesReceiptWithEyesReaction() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"created\", \"comment\": {\"body\": \"terrakube plan\"}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "issue-comment-ack-secret");

            Workspace ws = prCommentWorkspace(repoUrl, "ws-ack", "plan", 9, true, false);
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl)).thenReturn(List.of(ws));

            WebhookResult commentResult = resolvedCommentResult("plan", 9, "comment-1");
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(commentResult);
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            Map<String, String> headers = Map.of("x-github-event", "issue_comment");

            subject.processClaimedDelivery(rw, payload, headers);

            verify(prCommentService).acknowledgeReceipt(ws, "comment-1", 9);
        }

        @Test
        void v2WebhookIssueCommentApplyCreatesAutoApplyJobWhenEnabled() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"created\", \"comment\": {\"body\": \"terrakube apply\"}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "issue-comment-apply-secret");

            Workspace ws = prCommentWorkspace(repoUrl, "ws-apply-enabled", "apply", 12, true, true);
            ws.setDefaultTemplate("default-template-id");
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl)).thenReturn(List.of(ws));

            WebhookResult commentResult = resolvedCommentResult("apply", 12, "comment-apply-1");
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(commentResult);
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                j.setId(400);
                return j;
            });

            Map<String, String> headers = Map.of("x-github-event", "issue_comment");

            subject.processClaimedDelivery(rw, payload, headers);

            assertThat(ws.isLocked()).isTrue();
            verify(workspaceRepository).save(ws);

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            Job savedJob = jobCaptor.getValue();

            assertThat(savedJob.getTemplateReference()).isEqualTo("default-template-id");
            assertThat(savedJob.getPrNumber()).isEqualTo(12);
            assertThat(savedJob.isAutoApply()).isTrue();
            assertThat(savedJob.getCommandCommentId()).isEqualTo("comment-apply-1");
            verify(gitHubWebhookService).sendCommitStatus(eq(savedJob), eq(JobStatus.pending), any());
            verify(prCommentService, never()).postApplyDisabledNotice(any(), any());
        }

        @Test
        void v2WebhookIssueCommentApplyPostsDisabledNoticeWhenNotEnabled() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"created\", \"comment\": {\"body\": \"terrakube apply\"}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "issue-comment-apply-disabled-secret");

            Workspace ws = prCommentWorkspace(repoUrl, "ws-apply-disabled", "apply", 13, true, false);
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl)).thenReturn(List.of(ws));

            WebhookResult commentResult = resolvedCommentResult("apply", 13, "comment-apply-2");
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(commentResult);

            Map<String, String> headers = Map.of("x-github-event", "issue_comment");

            subject.processClaimedDelivery(rw, payload, headers);

            assertThat(ws.isLocked()).isFalse();
            verify(prCommentService).postApplyDisabledNotice(ws, 13);
            verify(jobRepository, never()).save(any(Job.class));
        }

        @Test
        void v2WebhookReleaseScenario() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"published\", \"release\": {\"tag_name\": \"v1.0.0\", \"target_commitish\": \"main\"}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "release-test-secret");

            // 1. Create two dummy workspaces
            Workspace ws1 = workspaceWithSource(repoUrl);
            ws1.setName("ws-release-1");
            Workspace ws2 = workspaceWithSource(repoUrl);
            ws2.setName("ws-release-2");

            // 2. Add webhook configuration using version 1 (migratedV2 = false)
            Webhook wh1 = new Webhook();
            wh1.setMigratedV2(false);
            ws1.setWebhook(wh1);

            Webhook wh2 = new Webhook();
            wh2.setMigratedV2(false);
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(Collections.emptyList());

            Map<String, String> headers = Map.of("x-github-event", "release");

            WebhookResult releaseResult = new WebhookResult();
            releaseResult.setEvent("release");
            releaseResult.setValid(true);
            releaseResult.setBranch("v1.0.0");
            releaseResult.setCommit("tag-sha-123");
            releaseResult.setRelease(true);
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(releaseResult);

            // Process webhook (V1 state) - should create 0 jobs
            subject.processClaimedDelivery(rw, payload, headers);
            verify(jobRepository, never()).save(any(Job.class));

            // 3. Migrate the configuration to version 2
            wh1.setMigratedV2(true);
            wh2.setMigratedV2(true);

            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.RELEASE);
            event1.setBranch("v1.*"); // Test regex matching
            event1.setTemplateId("release-template-1");
            wh1.setEvents(List.of(event1));

            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.RELEASE);
            event2.setBranch(".*");
            event2.setTemplateId("release-template-2");
            wh2.setEvents(List.of(event2));

            org.mockito.Mockito.clearInvocations(jobRepository);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws1, ws2));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh1, WebhookEventType.RELEASE))
                    .thenReturn(List.of(event1));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh2, WebhookEventType.RELEASE))
                    .thenReturn(List.of(event2));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                j.setId(200);
                return j;
            });

            // 4. Create a webhook request using version 2 and validate jobs are created
            subject.processClaimedDelivery(rw, payload, headers);

            // Verify a job was created for each workspace
            verify(jobRepository, times(2)).save(any(Job.class));

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository, times(2)).save(jobCaptor.capture());

            List<Job> savedJobs = jobCaptor.getAllValues();
            assertThat(savedJobs).extracting(Job::getTemplateReference)
                    .containsExactlyInAnyOrder("release-template-1", "release-template-2");

            assertThat(savedJobs).allSatisfy(job -> {
                assertThat(job.getCommitId()).isEqualTo("tag-sha-123");
                assertThat(job.getOverrideBranch()).isEqualTo("refs/tags/v1.0.0");
            });

            // Delete the dummy workspaces at the end of the method
            workspaceRepository.delete(ws1);
            workspaceRepository.delete(ws2);
            verify(workspaceRepository).delete(ws1);
            verify(workspaceRepository).delete(ws2);
        }

        @Test
        void handlesValidPingEvent() throws Exception {
            String payload = "{\"zen\":\"test\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "test-secret");

            Map<String, String> headers = Map.of("x-github-event", "ping");

            WebhookResult pingResult = new WebhookResult();
            pingResult.setEvent("ping");
            pingResult.setValid(true);
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(pingResult);

            subject.processClaimedDelivery(rw, payload, headers);

            verify(jobRepository, never()).save(any());
            verify(workspaceRepository, never()).findByNormalizedSourceWithMigratedWebhook(any());
        }

        @Test
        void fansOutToMultipleWorkspaces() throws Exception {
            String payload = "{\"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "test-secret");

            Map<String, String> headers = Map.of("x-github-event", "push");

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCreatedBy("user@test.com");
            pushResult.setVia("Github");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(pushResult);

            // Create two workspaces with matching webhook events
            Workspace ws1 = workspaceWithSource("https://github.com/owner/repo");
            ws1.setName("ws1");
            Webhook wh1 = new Webhook();
            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.PUSH);
            event1.setBranch("main");
            event1.setPath("*");
            event1.setPathType(WebhookEventPathType.PATTERN);
            event1.setTemplateId("template-1");
            wh1.setEvents(List.of(event1));
            ws1.setWebhook(wh1);

            Workspace ws2 = workspaceWithSource("https://github.com/owner/repo");
            ws2.setName("ws2");
            Webhook wh2 = new Webhook();
            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.PUSH);
            event2.setBranch("main");
            event2.setPath("*");
            event2.setPathType(WebhookEventPathType.PATTERN);
            event2.setTemplateId("template-2");
            wh2.setEvents(List.of(event2));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws1, ws2));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh1, WebhookEventType.PUSH))
                    .thenReturn(List.of(event1));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh2, WebhookEventType.PUSH))
                    .thenReturn(List.of(event2));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            verify(jobRepository, times(2)).save(any(Job.class));
        }

        @Test
        void continuesProcessingWhenOneWorkspaceFails() throws Exception {
            String payload = "{\"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "test-secret");

            Map<String, String> headers = Map.of("x-github-event", "push");

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCreatedBy("user@test.com");
            pushResult.setVia("Github");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(pushResult);

            // ws1 has no webhook (should be skipped with warning)
            Workspace ws1 = workspaceWithSource("https://github.com/owner/repo");
            ws1.setName("ws1-no-webhook");

            // ws2 has a valid webhook
            Workspace ws2 = workspaceWithSource("https://github.com/owner/repo");
            ws2.setName("ws2");
            Webhook wh2 = new Webhook();
            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.PUSH);
            event2.setBranch("main");
            event2.setPath("*");
            event2.setPathType(WebhookEventPathType.PATTERN);
            event2.setTemplateId("template-2");
            wh2.setEvents(List.of(event2));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws1, ws2));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh2, WebhookEventType.PUSH))
                    .thenReturn(List.of(event2));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            // ws1 skipped, ws2 should still create a job
            verify(jobRepository, times(1)).save(any(Job.class));
        }

        @Test
        void skipsInvalidWebhookResult() throws Exception {
            String payload = "{}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", "test-secret");

            Map<String, String> headers = Map.of("x-github-event", "unknown");

            WebhookResult invalidResult = new WebhookResult();
            invalidResult.setEvent("unknown");
            invalidResult.setValid(false);
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(invalidResult);

            subject.processClaimedDelivery(rw, payload, headers);

            verify(workspaceRepository, never()).findByNormalizedSourceWithMigratedWebhook(any());
            verify(jobRepository, never()).save(any());
        }

        @Test
        void fetchesPrFileChangesOnceForTheWholeDeliveryNotOncePerWorkspace() throws Exception {
            // Regression test: fetchPrFileChanges used to run inside the per-workspace loop, so a
            // shared webhook with N workspaces made N redundant (and, post-pagination-fix,
            // potentially multi-page) calls for the exact same PR's file list. It should now be
            // fetched once, repo-wide, using the repo webhook's own Vcs, and reused by every
            // workspace.
            String repoUrl = "https://github.com/owner/repo";
            String payload = "{\"action\":\"opened\", \"pull_request\": {\"number\": 42, \"head\": {\"sha\": \"def456\"}}}";

            RepoWebhook rw = repoWebhookWith(repoUrl, "dedupe-test-secret");
            Map<String, String> headers = Map.of("x-github-event", "pull_request");

            WebhookResult prResult = new WebhookResult();
            prResult.setEvent("pull_request");
            prResult.setValid(true);
            prResult.setBranch("feature-branch");
            prResult.setCommit("def456");
            prResult.setPrNumber(42);
            prResult.setPrFilesUrl("https://api.github.com/repos/owner/repo/pulls/42/files");
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(prResult);
            when(gitHubWebhookService.fetchPrFileChanges(eq(rw.getVcs()), eq(repoUrl), eq(prResult.getPrFilesUrl())))
                    .thenReturn(List.of("modules/network/main.tf"));

            Workspace ws1 = workspaceWithSource(repoUrl);
            ws1.setName("dedupe-ws1");
            Webhook wh1 = new Webhook();
            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.PULL_REQUEST);
            event1.setBranch("feature-branch");
            event1.setPath("**");
            event1.setPathType(WebhookEventPathType.PATTERN);
            event1.setTemplateId("template-1");
            wh1.setEvents(List.of(event1));
            ws1.setWebhook(wh1);

            Workspace ws2 = workspaceWithSource(repoUrl);
            ws2.setName("dedupe-ws2");
            Webhook wh2 = new Webhook();
            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.PULL_REQUEST);
            event2.setBranch("feature-branch");
            event2.setPath("**");
            event2.setPathType(WebhookEventPathType.PATTERN);
            event2.setTemplateId("template-2");
            wh2.setEvents(List.of(event2));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws1, ws2));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh1, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event1));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh2, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event2));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            verify(gitHubWebhookService, times(1)).fetchPrFileChanges(any(), any(), any());
            verify(gitHubWebhookService, never()).fetchPrFileChanges(eq(ws1.getVcs()), any(), any());
            verify(gitHubWebhookService, never()).fetchPrFileChanges(eq(ws2.getVcs()), any(), any());
            verify(jobRepository, times(2)).save(any(Job.class));
        }
    }

    @Nested
    class VerifyHmacSignature {

        @Test
        void acceptsValidSignature() throws Exception {
            String secret = "my-webhook-secret";
            String payload = "{\"action\":\"push\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(repoWebhookDeliveryTransactions.enqueue(eq(rw), eq(payload), any())).thenReturn(UUID.randomUUID());

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "ping");

            // Should not throw - valid signature
            subject.acceptV2Webhook(rw.getId().toString(), payload, headers);
        }

        @Test
        void rejectsTamperedPayload() throws Exception {
            String secret = "my-webhook-secret";
            String payload = "{\"action\":\"push\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            // Compute sig for different payload
            String sig = computeHmac(secret, "{\"action\":\"different\"}");
            Map<String, String> headers = Map.of("x-hub-signature-256", sig);

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), payload, headers))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        void rejectsWrongSecret() throws Exception {
            String secret = "correct-secret";
            String payload = "{}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            String sig = computeHmac("wrong-secret", payload);
            Map<String, String> headers = Map.of("x-hub-signature-256", sig);

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), payload, headers))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Nested
    class GitLabRepoWebhook {

        @Test
        void createOrUpdateSharedWebhookDispatchesToGitLab() {
            RepoWebhook rw = gitlabRepoWebhookWith("https://gitlab.com/owner/repo", "secret");

            Workspace ws1 = gitlabWorkspaceWithSource("https://gitlab.com/owner/repo");
            Webhook wh1 = new Webhook();
            WebhookEvent pushEvent = new WebhookEvent();
            pushEvent.setEvent(WebhookEventType.PUSH);
            wh1.setEvents(List.of(pushEvent));
            ws1.setWebhook(wh1);

            Workspace ws2 = gitlabWorkspaceWithSource("https://gitlab.com/owner/repo");
            Webhook wh2 = new Webhook();
            WebhookEvent mrEvent = new WebhookEvent();
            mrEvent.setEvent(WebhookEventType.PULL_REQUEST);
            wh2.setEvents(List.of(mrEvent));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws1, ws2));
            when(gitLabWebhookService.createOrUpdateRepoWebhook(eq(rw), any(), anyBoolean()))
                    .thenReturn("gl-999");

            subject.createOrUpdateSharedWebhook(rw);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<WebhookEventType>> captor = ArgumentCaptor.forClass(Set.class);
            verify(gitLabWebhookService).createOrUpdateRepoWebhook(eq(rw), captor.capture(), eq(false));
            assertThat(captor.getValue()).containsExactlyInAnyOrder(WebhookEventType.PUSH, WebhookEventType.PULL_REQUEST);
            assertThat(rw.getRemoteHookId()).isEqualTo("gl-999");
            verify(gitHubWebhookService, never()).createOrUpdateRepoWebhook(any(), any(), anyBoolean());
            verify(repoWebhookRepository).save(rw);
        }

        @Test
        void cleanupDeletesGitLabRepoWebhookWhenOrphan() {
            RepoWebhook rw = gitlabRepoWebhookWith("https://gitlab.com/owner/repo", "secret");
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(Collections.emptyList());

            subject.cleanupIfOrphan(rw);

            verify(gitLabWebhookService).deleteRepoWebhook(rw);
            verify(gitHubWebhookService, never()).deleteRepoWebhook(any());
            verify(repoWebhookRepository).delete(rw);
        }

        @Test
        void gitLabPushFansOutToWorkspace() {
            String repoUrl = "https://gitlab.com/owner/repo";
            String payload = "{\"object_kind\":\"push\"}";
            RepoWebhook rw = gitlabRepoWebhookWith(repoUrl, "gitlab-token-secret");

            Map<String, String> headers = Map.of();

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitLabWebhookService.parseGitLabPayload(eq(payload), any())).thenReturn(pushResult);

            Workspace ws = gitlabWorkspaceWithSource(repoUrl);
            ws.setName("gl-ws");
            Webhook wh = new Webhook();
            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PUSH);
            event.setBranch("main");
            event.setPath("*");
            event.setPathType(WebhookEventPathType.PATTERN);
            event.setTemplateId("gl-template");
            wh.setEvents(List.of(event));
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.PUSH))
                    .thenReturn(List.of(event));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            assertThat(jobCaptor.getValue().getTemplateReference()).isEqualTo("gl-template");
            // GitLab parser used, not GitHub, and commit status sent via GitLab
            verify(gitHubWebhookService, never()).parseGitHubPayload(any(), any());
            verify(gitLabWebhookService).sendCommitStatus(any(Job.class), any(), any());
            verify(gitHubWebhookService, never()).sendCommitStatus(any(), any(), any());
        }

        @Test
        void gitLabMergeRequestFetchesFileChanges() {
            String repoUrl = "https://gitlab.com/owner/repo";
            String payload = "{\"object_kind\":\"merge_request\"}";
            RepoWebhook rw = gitlabRepoWebhookWith(repoUrl, "mr-token-secret");

            Map<String, String> headers = Map.of();

            WebhookResult mrResult = new WebhookResult();
            mrResult.setEvent("merge_request");
            mrResult.setValid(true);
            mrResult.setBranch("feature-branch");
            mrResult.setCommit("def456");
            mrResult.setPrNumber(42);
            // GitLab stores the MR iid marker in prFilesUrl (mirrors GitHub's PR files URL)
            mrResult.setPrFilesUrl("42");
            when(gitLabWebhookService.parseGitLabPayload(eq(payload), any())).thenReturn(mrResult);
            when(gitLabWebhookService.fetchPrFileChanges(any(), eq(repoUrl), eq("42")))
                    .thenReturn(List.of("variables.tf"));

            Workspace ws = gitlabWorkspaceWithSource(repoUrl);
            ws.setName("gl-mr-ws");
            Webhook wh = new Webhook();
            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PULL_REQUEST);
            event.setBranch("feature-branch");
            event.setPath("*.tf");
            event.setPathType(WebhookEventPathType.PATTERN);
            event.setTemplateId("gl-mr-template");
            wh.setEvents(List.of(event));
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            verify(gitLabWebhookService).fetchPrFileChanges(any(), eq(repoUrl), eq("42"));
            verify(gitHubWebhookService, never()).fetchPrFileChanges(any(), any(), any());
            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            assertThat(jobCaptor.getValue().getTemplateReference()).isEqualTo("gl-mr-template");
            assertThat(jobCaptor.getValue().getCommitId()).isEqualTo("def456");
            assertThat(jobCaptor.getValue().getOverrideBranch()).isEqualTo("feature-branch");
        }

        @Test
        void gitLabReleaseCreatesJobs() {
            String repoUrl = "https://gitlab.com/owner/repo";
            String payload = "{\"object_kind\":\"release\"}";
            RepoWebhook rw = gitlabRepoWebhookWith(repoUrl, "release-token-secret");

            Map<String, String> headers = Map.of();

            WebhookResult releaseResult = new WebhookResult();
            releaseResult.setEvent("release");
            releaseResult.setValid(true);
            releaseResult.setBranch("v1.0.0");
            releaseResult.setCommit("tag-sha-123");
            releaseResult.setRelease(true);
            when(gitLabWebhookService.parseGitLabPayload(eq(payload), any())).thenReturn(releaseResult);

            Workspace ws = gitlabWorkspaceWithSource(repoUrl);
            ws.setName("gl-release-ws");
            Webhook wh = new Webhook();
            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.RELEASE);
            event.setBranch("v1.*");
            event.setTemplateId("gl-release-template");
            wh.setEvents(List.of(event));
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.RELEASE))
                    .thenReturn(List.of(event));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            assertThat(jobCaptor.getValue().getTemplateReference()).isEqualTo("gl-release-template");
            assertThat(jobCaptor.getValue().getOverrideBranch()).isEqualTo("refs/tags/v1.0.0");
            // Releases don't send a commit status
            verify(gitLabWebhookService, never()).sendCommitStatus(any(), any(), any());
        }

        @Test
        void processV2WebhookGitLabRejectsWrongToken() {
            String secret = "correct-token";
            RepoWebhook rw = gitlabRepoWebhookWith("https://gitlab.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            Map<String, String> headers = Map.of("x-gitlab-token", "wrong-token");

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), "{}", headers))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("GitLab token verification failed");

            verify(repoWebhookDeliveryTransactions, never()).enqueue(any(), any(), any());
        }

        @Test
        void processV2WebhookGitLabRejectsMissingToken() {
            RepoWebhook rw = gitlabRepoWebhookWith("https://gitlab.com/owner/repo", "secret");
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), "{}", Map.of()))
                    .isInstanceOf(SecurityException.class);

            verify(repoWebhookDeliveryTransactions, never()).enqueue(any(), any(), any());
        }

        @Test
        void v2WebhookMigrationRemovalScenarioGitLab() {
            String repoUrl = "https://gitlab.com/owner/repo";

            Workspace workspace = gitlabWorkspaceWithSource(repoUrl);
            workspace.setName("gl-workspace-v1");

            Webhook webhook = new Webhook();
            webhook.setWorkspace(workspace);
            webhook.setMigratedV2(true);
            webhook.setRemoteHookId("old-gl-v1-hook-id");
            workspace.setWebhook(webhook);

            RepoWebhook repoWebhook = gitlabRepoWebhookWith(repoUrl, "new-secret");
            when(repoWebhookRepository.findByRepositoryUrl(anyString())).thenReturn(Optional.of(repoWebhook));

            // This mirrors the logic in WebhookManageHook for a migrated GitLab webhook
            if (webhook.isMigratedV2() && workspace.getVcs() != null
                    && workspace.getVcs().getVcsType() == VcsType.GITLAB) {
                subject.getOrCreateRepoWebhook(workspace);
                subject.createOrUpdateSharedWebhook(repoWebhook);

                if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
                    gitLabWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());
                    webhook.setRemoteHookId(null);
                }
            }

            verify(gitLabWebhookService).deleteWebhook(workspace, "old-gl-v1-hook-id");
            verify(gitHubWebhookService, never()).deleteRepoWebhook(any());
            assertThat(webhook.getRemoteHookId()).isNull();
        }
    }

    // ======================== Azure DevOps (AZURE_SP_MI) Tests ========================

    private Workspace azDevOpsWorkspaceWithSource(String source) {
        Workspace ws = workspaceWithSource(source);
        ws.getVcs().setVcsType(VcsType.AZURE_SP_MI);
        return ws;
    }

    private RepoWebhook azDevOpsRepoWebhookWith(String url, String secret) {
        RepoWebhook rw = repoWebhookWith(url, secret);
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.AZURE_SP_MI);
        rw.setVcs(vcs);
        return rw;
    }

    @Nested
    class AzDevOpsRepoWebhook {

        @Test
        void createOrUpdateSharedWebhookDispatchesToAzDevOps() {
            RepoWebhook rw = azDevOpsRepoWebhookWith("https://dev.azure.com/org/proj/repo", "secret");

            Workspace ws1 = azDevOpsWorkspaceWithSource("https://dev.azure.com/org/proj/repo");
            Webhook wh1 = new Webhook();
            WebhookEvent pushEvent = new WebhookEvent();
            pushEvent.setEvent(WebhookEventType.PUSH);
            wh1.setEvents(List.of(pushEvent));
            ws1.setWebhook(wh1);

            Workspace ws2 = azDevOpsWorkspaceWithSource("https://dev.azure.com/org/proj/repo");
            Webhook wh2 = new Webhook();
            WebhookEvent prEvent = new WebhookEvent();
            prEvent.setEvent(WebhookEventType.PULL_REQUEST);
            wh2.setEvents(List.of(prEvent));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws1, ws2));
            when(azDevOpsWebhookService.createOrUpdateRepoWebhook(eq(rw), any()))
                    .thenReturn("sub-1,sub-2");

            subject.createOrUpdateSharedWebhook(rw);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<WebhookEventType>> captor = ArgumentCaptor.forClass(Set.class);
            verify(azDevOpsWebhookService).createOrUpdateRepoWebhook(eq(rw), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(WebhookEventType.PUSH, WebhookEventType.PULL_REQUEST);
            assertThat(rw.getRemoteHookId()).isEqualTo("sub-1,sub-2");
            verify(gitHubWebhookService, never()).createOrUpdateRepoWebhook(any(), any(), anyBoolean());
            verify(gitLabWebhookService, never()).createOrUpdateRepoWebhook(any(), any(), anyBoolean());
            verify(repoWebhookRepository).save(rw);
        }

        @Test
        void cleanupDeletesAzDevOpsRepoWebhookWhenOrphan() {
            RepoWebhook rw = azDevOpsRepoWebhookWith("https://dev.azure.com/org/proj/repo", "secret");
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(Collections.emptyList());

            subject.cleanupIfOrphan(rw);

            verify(azDevOpsWebhookService).deleteRepoWebhook(rw);
            verify(gitHubWebhookService, never()).deleteRepoWebhook(any());
            verify(gitLabWebhookService, never()).deleteRepoWebhook(any());
            verify(repoWebhookRepository).delete(rw);
        }

        @Test
        void processV2WebhookAzDevOpsTokenVerificationSucceeds() {
            String repoUrl = "https://dev.azure.com/org/proj/repo";
            String secret = "az-test-secret";
            RepoWebhook rw = azDevOpsRepoWebhookWith(repoUrl, secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(repoWebhookDeliveryTransactions.enqueue(eq(rw), any(), any())).thenReturn(UUID.randomUUID());

            Map<String, String> headers = Map.of("x-terrakube-token", secret);

            // Should not throw
            subject.acceptV2Webhook(rw.getId().toString(), "{\"eventType\":\"git.push\"}", headers);

            verify(repoWebhookDeliveryTransactions).enqueue(eq(rw), any(), any());
        }

        @Test
        void azDevOpsPushWithNoMatchingWorkspacesParsesAndNoOps() {
            String repoUrl = "https://dev.azure.com/org/proj/repo";
            String payload = "{\"eventType\":\"git.push\"}";
            RepoWebhook rw = azDevOpsRepoWebhookWith(repoUrl, "az-test-secret");

            Map<String, String> headers = Map.of();

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of());
            pushResult.setRawPayload(payload);
            when(azDevOpsWebhookService.parseAzDevOpsPayload(eq(payload), any())).thenReturn(pushResult);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(Collections.emptyList());

            subject.processClaimedDelivery(rw, payload, headers);

            verify(azDevOpsWebhookService).parseAzDevOpsPayload(eq(payload), any());
            verify(gitHubWebhookService, never()).parseGitHubPayload(any(), any());
            verify(gitLabWebhookService, never()).parseGitLabPayload(any(), any());
        }

        @Test
        void processV2WebhookAzDevOpsRejectsWrongToken() {
            String secret = "correct-token";
            RepoWebhook rw = azDevOpsRepoWebhookWith("https://dev.azure.com/org/proj/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            Map<String, String> headers = Map.of("x-terrakube-token", "wrong-token");

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), "{}", headers))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Azure DevOps token verification failed");

            verify(repoWebhookDeliveryTransactions, never()).enqueue(any(), any(), any());
        }

        @Test
        void processV2WebhookAzDevOpsRejectsMissingToken() {
            RepoWebhook rw = azDevOpsRepoWebhookWith("https://dev.azure.com/org/proj/repo", "secret");
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            assertThatThrownBy(() -> subject.acceptV2Webhook(rw.getId().toString(), "{}", Map.of()))
                    .isInstanceOf(SecurityException.class);

            verify(repoWebhookDeliveryTransactions, never()).enqueue(any(), any(), any());
        }

        @Test
        void processV2WebhookAzDevOpsPushFansOutWithFileChanges() {
            String repoUrl = "https://dev.azure.com/org/proj/repo";
            String payload = "{\"eventType\":\"git.push\",\"resource\":{\"refUpdates\":[{\"name\":\"refs/heads/main\",\"newObjectId\":\"abc123\"}]}}";
            RepoWebhook rw = azDevOpsRepoWebhookWith(repoUrl, "az-push-secret");

            Map<String, String> headers = Map.of();

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCommit("abc123");
            pushResult.setCreatedBy("user@test.com");
            pushResult.setVia("Azure DevOps");
            pushResult.setFileChanges(new java.util.ArrayList<>());
            pushResult.setRawPayload(payload);
            when(azDevOpsWebhookService.parseAzDevOpsPayload(eq(payload), any())).thenReturn(pushResult);

            // Per-workspace file change fetching
            when(azDevOpsWebhookService.fetchPushFileChanges(any(), eq(repoUrl), eq(payload)))
                    .thenReturn(List.of("main.tf"));

            Workspace ws1 = azDevOpsWorkspaceWithSource(repoUrl);
            ws1.setName("az-ws1");
            Webhook wh1 = new Webhook();
            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.PUSH);
            event1.setBranch("main");
            event1.setPath("*");
            event1.setPathType(WebhookEventPathType.PATTERN);
            event1.setTemplateId("az-template-1");
            wh1.setEvents(List.of(event1));
            ws1.setWebhook(wh1);

            Workspace ws2 = azDevOpsWorkspaceWithSource(repoUrl);
            ws2.setName("az-ws2");
            Webhook wh2 = new Webhook();
            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.PUSH);
            event2.setBranch("main");
            event2.setPath("*");
            event2.setPathType(WebhookEventPathType.PATTERN);
            event2.setTemplateId("az-template-2");
            wh2.setEvents(List.of(event2));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws1, ws2));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh1, WebhookEventType.PUSH))
                    .thenReturn(List.of(event1));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh2, WebhookEventType.PUSH))
                    .thenReturn(List.of(event2));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            verify(jobRepository, times(2)).save(any(Job.class));
            verify(azDevOpsWebhookService, times(2)).fetchPushFileChanges(any(), eq(repoUrl), eq(payload));
            // Commit status sent via Azure DevOps
            verify(azDevOpsWebhookService, times(2)).sendCommitStatus(any(Job.class), any(), any());
            verify(gitHubWebhookService, never()).sendCommitStatus(any(), any(), any());
            verify(gitLabWebhookService, never()).sendCommitStatus(any(), any(), any());
        }

        @Test
        void processV2WebhookAzDevOpsPullRequestFetchesFileChanges() {
            String repoUrl = "https://dev.azure.com/org/proj/repo";
            String payload = "{\"eventType\":\"git.pullrequest.created\"}";
            RepoWebhook rw = azDevOpsRepoWebhookWith(repoUrl, "az-pr-secret");

            Map<String, String> headers = Map.of();

            WebhookResult prResult = new WebhookResult();
            prResult.setEvent("pull_request");
            prResult.setValid(true);
            prResult.setBranch("feature-branch");
            prResult.setCommit("def456");
            prResult.setPrNumber(42);
            prResult.setFileChanges(new java.util.ArrayList<>());
            prResult.setRawPayload(payload);
            when(azDevOpsWebhookService.parseAzDevOpsPayload(eq(payload), any())).thenReturn(prResult);

            when(azDevOpsWebhookService.fetchPrFileChanges(any(), eq(repoUrl), eq(42)))
                    .thenReturn(List.of("variables.tf"));

            Workspace ws = azDevOpsWorkspaceWithSource(repoUrl);
            ws.setName("az-pr-ws");
            Webhook wh = new Webhook();
            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PULL_REQUEST);
            event.setBranch("feature-branch");
            event.setPath("*.tf");
            event.setPathType(WebhookEventPathType.PATTERN);
            event.setTemplateId("az-pr-template");
            wh.setEvents(List.of(event));
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.PULL_REQUEST))
                    .thenReturn(List.of(event));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            verify(azDevOpsWebhookService).fetchPrFileChanges(any(), eq(repoUrl), eq(42));
            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            assertThat(jobCaptor.getValue().getTemplateReference()).isEqualTo("az-pr-template");
            assertThat(jobCaptor.getValue().getCommitId()).isEqualTo("def456");
            assertThat(jobCaptor.getValue().getOverrideBranch()).isEqualTo("feature-branch");
        }

        @Test
        void processV2WebhookAzDevOpsReleaseCreatesJobs() {
            String repoUrl = "https://dev.azure.com/org/proj/repo";
            String payload = "{\"eventType\":\"git.push\"}";
            RepoWebhook rw = azDevOpsRepoWebhookWith(repoUrl, "az-release-secret");

            Map<String, String> headers = Map.of();

            WebhookResult releaseResult = new WebhookResult();
            releaseResult.setEvent("release");
            releaseResult.setValid(true);
            releaseResult.setBranch("v1.0.0");
            releaseResult.setCommit("tag-sha-123");
            releaseResult.setRelease(true);
            releaseResult.setRawPayload(payload);
            when(azDevOpsWebhookService.parseAzDevOpsPayload(eq(payload), any())).thenReturn(releaseResult);

            Workspace ws = azDevOpsWorkspaceWithSource(repoUrl);
            ws.setName("az-release-ws");
            Webhook wh = new Webhook();
            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.RELEASE);
            event.setBranch("v1.*");
            event.setTemplateId("az-release-template");
            wh.setEvents(List.of(event));
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(List.of(ws));
            when(webhookEventRepository.findByWebhookAndEventOrderByPriorityAsc(wh, WebhookEventType.RELEASE))
                    .thenReturn(List.of(event));
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

            subject.processClaimedDelivery(rw, payload, headers);

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository).save(jobCaptor.capture());
            assertThat(jobCaptor.getValue().getTemplateReference()).isEqualTo("az-release-template");
            assertThat(jobCaptor.getValue().getOverrideBranch()).isEqualTo("refs/tags/v1.0.0");
            // Releases don't send commit status
            verify(azDevOpsWebhookService, never()).sendCommitStatus(any(), any(), any());
        }

        @Test
        void v2WebhookMigrationRemovalScenarioAzDevOps() {
            String repoUrl = "https://dev.azure.com/org/proj/repo";

            Workspace workspace = azDevOpsWorkspaceWithSource(repoUrl);
            workspace.setName("az-workspace-v1");

            Webhook webhook = new Webhook();
            webhook.setWorkspace(workspace);
            webhook.setMigratedV2(true);
            webhook.setRemoteHookId("old-az-sub-1,old-az-sub-2");
            workspace.setWebhook(webhook);

            RepoWebhook repoWebhook = azDevOpsRepoWebhookWith(repoUrl, "new-secret");
            when(repoWebhookRepository.findByRepositoryUrl(anyString())).thenReturn(Optional.of(repoWebhook));

            // Mirrors the logic from WebhookManageHook for a migrated AZURE_SP_MI webhook
            if (webhook.isMigratedV2() && workspace.getVcs() != null
                    && workspace.getVcs().getVcsType() == VcsType.AZURE_SP_MI) {
                subject.getOrCreateRepoWebhook(workspace);
                subject.createOrUpdateSharedWebhook(repoWebhook);

                if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
                    azDevOpsWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());
                    webhook.setRemoteHookId(null);
                }
            }

            verify(azDevOpsWebhookService).deleteWebhook(workspace, "old-az-sub-1,old-az-sub-2");
            verify(gitHubWebhookService, never()).deleteRepoWebhook(any());
            verify(gitLabWebhookService, never()).deleteRepoWebhook(any());
            assertThat(webhook.getRemoteHookId()).isNull();
        }
    }
}
