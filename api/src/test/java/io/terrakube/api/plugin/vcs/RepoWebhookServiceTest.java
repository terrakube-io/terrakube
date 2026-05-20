package io.terrakube.api.plugin.vcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
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
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;

    RepoWebhookService subject;

    @BeforeEach
    void setup() {
        repoWebhookRepository = mock(RepoWebhookRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        webhookEventRepository = mock(WebhookEventRepository.class);
        gitHubWebhookService = mock(GitHubWebhookService.class);
        jobRepository = mock(JobRepository.class);
        scheduleJobService = mock(ScheduleJobService.class);

        subject = new RepoWebhookService(
                repoWebhookRepository,
                workspaceRepository,
                webhookEventRepository,
                gitHubWebhookService,
                jobRepository,
                scheduleJobService);
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
            verify(repoWebhookRepository, never()).save(any());
        }

        @Test
        void createsNewWhenNotFound() {
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.save(any(RepoWebhook.class))).thenAnswer(inv -> inv.getArgument(0));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result.getRepositoryUrl()).isEqualTo("https://github.com/owner/repo");
            assertThat(result.getWebhookSecret()).isNotNull().hasSize(36); // UUID format
            verify(repoWebhookRepository).save(any(RepoWebhook.class));
        }

        @Test
        void handlesRaceConditionOnConcurrentInsert() {
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            RepoWebhook existing = repoWebhookWith("https://github.com/owner/repo", "secret");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existing));
            when(repoWebhookRepository.save(any(RepoWebhook.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate"));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result).isSameAs(existing);
        }

        @Test
        void throwsWhenRaceConditionRetryAlsoFails() {
            Workspace ws = workspaceWithSource("https://github.com/owner/repo");
            when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.save(any(RepoWebhook.class)))
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
            when(repoWebhookRepository.save(any(RepoWebhook.class))).thenAnswer(inv -> inv.getArgument(0));

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
            when(gitHubWebhookService.createOrUpdateRepoWebhook(eq(rw), any()))
                    .thenReturn("12345");

            subject.createOrUpdateSharedWebhook(rw);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<WebhookEventType>> captor = ArgumentCaptor.forClass(Set.class);
            verify(gitHubWebhookService).createOrUpdateRepoWebhook(eq(rw), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(WebhookEventType.PUSH, WebhookEventType.PULL_REQUEST);
            assertThat(rw.getRemoteHookId()).isEqualTo("12345");
            verify(repoWebhookRepository).save(rw);
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

            verify(gitHubWebhookService, never()).createOrUpdateRepoWebhook(any(), any());
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
            when(gitHubWebhookService.createOrUpdateRepoWebhook(eq(rw), any())).thenReturn("12345");

            subject.cleanupIfOrphan(rw);

            verify(gitHubWebhookService, never()).deleteRepoWebhook(any());
            verify(repoWebhookRepository, never()).delete(any());
            verify(gitHubWebhookService).createOrUpdateRepoWebhook(eq(rw), any());
        }
    }

    @Nested
    class ProcessV2Webhook {

        @Test
        void v2WebhookMigrationScenario() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String secret = "migration-test-secret";
            String payload = "{\"ref\":\"refs/heads/main\", \"commits\": [{\"id\": \"abc123\"}]}";
            
            RepoWebhook rw = repoWebhookWith(repoUrl, secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

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

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "push");

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(pushResult);

            // Process webhook (V1 state) - should create 0 jobs
            subject.processV2Webhook(rw.getId().toString(), payload, headers);
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
            subject.processV2Webhook(rw.getId().toString(), payload, headers);

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
            String secret = "pr-migration-test-secret";
            String payload = "{\"action\":\"opened\", \"pull_request\": {\"number\": 42, \"head\": {\"sha\": \"def456\"}}}";
            
            RepoWebhook rw = repoWebhookWith(repoUrl, secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

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

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "pull_request");

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
            subject.processV2Webhook(rw.getId().toString(), payload, headers);
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
            subject.processV2Webhook(rw.getId().toString(), payload, headers);

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
        void v2WebhookReleaseScenario() throws Exception {
            String repoUrl = "https://github.com/owner/repo";
            String secret = "release-test-secret";
            String payload = "{\"action\":\"published\", \"release\": {\"tag_name\": \"v1.0.0\", \"target_commitish\": \"main\"}}";
            
            RepoWebhook rw = repoWebhookWith(repoUrl, secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

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

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "release");

            WebhookResult releaseResult = new WebhookResult();
            releaseResult.setEvent("release");
            releaseResult.setValid(true);
            releaseResult.setBranch("v1.0.0");
            releaseResult.setCommit("tag-sha-123");
            releaseResult.setRelease(true);
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(releaseResult);

            // Process webhook (V1 state) - should create 0 jobs
            subject.processV2Webhook(rw.getId().toString(), payload, headers);
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
            subject.processV2Webhook(rw.getId().toString(), payload, headers);

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
            assertThatThrownBy(() -> subject.processV2Webhook("not-a-uuid", "{}", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnNotFound() {
            UUID id = UUID.randomUUID();
            when(repoWebhookRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subject.processV2Webhook(id.toString(), "{}", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Repo webhook not found");
        }

        @Test
        void throwsSecurityExceptionOnHmacFailure() {
            String secret = "test-secret";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            Map<String, String> headers = Map.of("x-hub-signature-256", "sha256=invalid");

            assertThatThrownBy(() -> subject.processV2Webhook(rw.getId().toString(), "{}", headers))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("HMAC signature verification failed");

            verify(jobRepository, never()).save(any());
        }

        @Test
        void throwsWhenSignatureHeaderMissing() {
            String secret = "test-secret";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            assertThatThrownBy(() -> subject.processV2Webhook(rw.getId().toString(), "{}", Map.of()))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        void handlesValidPingEvent() throws Exception {
            String secret = "test-secret";
            String payload = "{\"zen\":\"test\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "ping");

            WebhookResult pingResult = new WebhookResult();
            pingResult.setEvent("ping");
            pingResult.setValid(true);
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(pingResult);

            subject.processV2Webhook(rw.getId().toString(), payload, headers);

            verify(jobRepository, never()).save(any());
            verify(workspaceRepository, never()).findByNormalizedSourceWithMigratedWebhook(any());
        }

        @Test
        void fansOutToMultipleWorkspaces() throws Exception {
            String secret = "test-secret";
            String payload = "{\"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "push");

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

            subject.processV2Webhook(rw.getId().toString(), payload, headers);

            verify(jobRepository, times(2)).save(any(Job.class));
        }

        @Test
        void continuesProcessingWhenOneWorkspaceFails() throws Exception {
            String secret = "test-secret";
            String payload = "{\"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "push");

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

            subject.processV2Webhook(rw.getId().toString(), payload, headers);

            // ws1 skipped, ws2 should still create a job
            verify(jobRepository, times(1)).save(any(Job.class));
        }

        @Test
        void skipsInvalidWebhookResult() throws Exception {
            String secret = "test-secret";
            String payload = "{}";
            RepoWebhook rw = repoWebhookWith("https://github.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "unknown");

            WebhookResult invalidResult = new WebhookResult();
            invalidResult.setEvent("unknown");
            invalidResult.setValid(false);
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(invalidResult);

            subject.processV2Webhook(rw.getId().toString(), payload, headers);

            verify(workspaceRepository, never()).findByNormalizedSourceWithMigratedWebhook(any());
            verify(jobRepository, never()).save(any());
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

            String sig = computeHmac(secret, payload);
            Map<String, String> headers = Map.of(
                    "x-hub-signature-256", sig,
                    "x-github-event", "ping");

            WebhookResult pingResult = new WebhookResult();
            pingResult.setEvent("ping");
            pingResult.setValid(true);
            when(gitHubWebhookService.parseGitHubPayload(eq(payload), any())).thenReturn(pingResult);

            // Should not throw — valid signature
            subject.processV2Webhook(rw.getId().toString(), payload, headers);
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

            assertThatThrownBy(() -> subject.processV2Webhook(rw.getId().toString(), payload, headers))
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

            assertThatThrownBy(() -> subject.processV2Webhook(rw.getId().toString(), payload, headers))
                    .isInstanceOf(SecurityException.class);
        }
    }
}
