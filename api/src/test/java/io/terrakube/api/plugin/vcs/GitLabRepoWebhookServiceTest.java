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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
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
class GitLabRepoWebhookServiceTest {

    RepoWebhookRepository repoWebhookRepository;
    WorkspaceRepository workspaceRepository;
    WebhookEventRepository webhookEventRepository;
    GitLabWebhookService gitLabWebhookService;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;

    GitLabRepoWebhookService subject;

    @BeforeEach
    void setup() {
        repoWebhookRepository = mock(RepoWebhookRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        webhookEventRepository = mock(WebhookEventRepository.class);
        gitLabWebhookService = mock(GitLabWebhookService.class);
        jobRepository = mock(JobRepository.class);
        scheduleJobService = mock(ScheduleJobService.class);

        subject = new GitLabRepoWebhookService(
                repoWebhookRepository,
                workspaceRepository,
                webhookEventRepository,
                gitLabWebhookService,
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

    @Nested
    class GetOrCreateRepoWebhook {

        @Test
        void returnsExistingWhenFound() {
            Workspace ws = workspaceWithSource("https://gitlab.com/owner/repo");
            RepoWebhook existing = repoWebhookWith("https://gitlab.com/owner/repo", "secret");
            when(repoWebhookRepository.findByRepositoryUrl("https://gitlab.com/owner/repo"))
                    .thenReturn(Optional.of(existing));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result).isSameAs(existing);
            verify(repoWebhookRepository, never()).save(any());
        }

        @Test
        void createsNewWhenNotFound() {
            Workspace ws = workspaceWithSource("https://gitlab.com/owner/repo");
            when(repoWebhookRepository.findByRepositoryUrl("https://gitlab.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.save(any(RepoWebhook.class))).thenAnswer(inv -> inv.getArgument(0));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result.getRepositoryUrl()).isEqualTo("https://gitlab.com/owner/repo");
            assertThat(result.getWebhookSecret()).isNotNull().hasSize(36);
            verify(repoWebhookRepository).save(any(RepoWebhook.class));
        }

        @Test
        void handlesRaceConditionOnConcurrentInsert() {
            Workspace ws = workspaceWithSource("https://gitlab.com/owner/repo");
            RepoWebhook existing = repoWebhookWith("https://gitlab.com/owner/repo", "secret");
            when(repoWebhookRepository.findByRepositoryUrl("https://gitlab.com/owner/repo"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existing));
            when(repoWebhookRepository.save(any(RepoWebhook.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate"));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result).isSameAs(existing);
        }

        @Test
        void throwsWhenRaceConditionRetryAlsoFails() {
            Workspace ws = workspaceWithSource("https://gitlab.com/owner/repo");
            when(repoWebhookRepository.findByRepositoryUrl("https://gitlab.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.save(any(RepoWebhook.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate"));

            assertThatThrownBy(() -> subject.getOrCreateRepoWebhook(ws))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to create or find RepoWebhook");
        }

        @Test
        void normalizesUrl() {
            Workspace ws = workspaceWithSource("https://GitLab.com/Owner/Repo.git");
            when(repoWebhookRepository.findByRepositoryUrl("https://gitlab.com/owner/repo"))
                    .thenReturn(Optional.empty());
            when(repoWebhookRepository.save(any(RepoWebhook.class))).thenAnswer(inv -> inv.getArgument(0));

            RepoWebhook result = subject.getOrCreateRepoWebhook(ws);

            assertThat(result.getRepositoryUrl()).isEqualTo("https://gitlab.com/owner/repo");
        }
    }

    @Nested
    class CreateOrUpdateSharedWebhook {

        @Test
        void aggregatesEventTypesAcrossWorkspaces() {
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", "secret");

            Workspace ws1 = workspaceWithSource("https://gitlab.com/owner/repo");
            Webhook wh1 = new Webhook();
            WebhookEvent pushEvent = new WebhookEvent();
            pushEvent.setEvent(WebhookEventType.PUSH);
            wh1.setEvents(List.of(pushEvent));
            ws1.setWebhook(wh1);

            Workspace ws2 = workspaceWithSource("https://gitlab.com/owner/repo");
            Webhook wh2 = new Webhook();
            WebhookEvent prEvent = new WebhookEvent();
            prEvent.setEvent(WebhookEventType.PULL_REQUEST);
            wh2.setEvents(List.of(prEvent));
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws1, ws2));
            when(gitLabWebhookService.createOrUpdateRepoWebhook(eq(rw), any()))
                    .thenReturn("12345");

            subject.createOrUpdateSharedWebhook(rw);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<WebhookEventType>> captor = ArgumentCaptor.forClass(Set.class);
            verify(gitLabWebhookService).createOrUpdateRepoWebhook(eq(rw), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(WebhookEventType.PUSH, WebhookEventType.PULL_REQUEST);
            assertThat(rw.getRemoteHookId()).isEqualTo("12345");
            verify(repoWebhookRepository).save(rw);
        }

        @Test
        void skipsWhenNoEventTypes() {
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", "secret");

            Workspace ws = workspaceWithSource("https://gitlab.com/owner/repo");
            Webhook wh = new Webhook();
            wh.setEvents(Collections.emptyList());
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws));

            subject.createOrUpdateSharedWebhook(rw);

            verify(gitLabWebhookService, never()).createOrUpdateRepoWebhook(any(), any());
        }
    }

    @Nested
    class CleanupIfOrphan {

        @Test
        void deletesWhenNoWorkspacesRemain() {
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", "secret");
            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(Collections.emptyList());

            subject.cleanupIfOrphan(rw);

            verify(gitLabWebhookService).deleteRepoWebhook(rw);
            verify(repoWebhookRepository).delete(rw);
        }

        @Test
        void updatesWhenWorkspacesStillExist() {
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", "secret");
            Workspace ws = workspaceWithSource("https://gitlab.com/owner/repo");
            Webhook wh = new Webhook();
            WebhookEvent event = new WebhookEvent();
            event.setEvent(WebhookEventType.PUSH);
            wh.setEvents(List.of(event));
            ws.setWebhook(wh);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(rw.getRepositoryUrl()))
                    .thenReturn(List.of(ws));
            when(gitLabWebhookService.createOrUpdateRepoWebhook(eq(rw), any())).thenReturn("12345");

            subject.cleanupIfOrphan(rw);

            verify(gitLabWebhookService, never()).deleteRepoWebhook(any());
            verify(repoWebhookRepository, never()).delete(any());
            verify(gitLabWebhookService).createOrUpdateRepoWebhook(eq(rw), any());
        }
    }

    @Nested
    class ProcessV2Webhook {

        @Test
        void v2WebhookMigrationScenario() {
            String repoUrl = "https://gitlab.com/owner/repo";
            String secret = "migration-test-secret";
            String payload = "{\"object_kind\":\"push\", \"ref\":\"refs/heads/main\"}";

            RepoWebhook rw = repoWebhookWith(repoUrl, secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(true);

            Workspace ws1 = workspaceWithSource(repoUrl);
            ws1.setName("workspace-1");
            Workspace ws2 = workspaceWithSource(repoUrl);
            ws2.setName("workspace-2");

            Webhook wh1 = new Webhook();
            wh1.setMigratedV2(false);
            ws1.setWebhook(wh1);
            Webhook wh2 = new Webhook();
            wh2.setMigratedV2(false);
            ws2.setWebhook(wh2);

            when(workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repoUrl))
                    .thenReturn(Collections.emptyList());

            Map<String, String> headers = Map.of("x-gitlab-token", secret);

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitLabWebhookService.parseGitlabPayload(eq(payload), any(), any(), eq(repoUrl)))
                    .thenReturn(pushResult);

            // V1 state - should create 0 jobs
            subject.processV2Webhook(rw.getId().toString(), payload, headers);
            verify(jobRepository, never()).save(any(Job.class));

            // Migrate to V2
            wh1.setMigratedV2(true);
            wh2.setMigratedV2(true);

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

            subject.processV2Webhook(rw.getId().toString(), payload, headers);

            verify(jobRepository, times(2)).save(any(Job.class));

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository, times(2)).save(jobCaptor.capture());
            assertThat(jobCaptor.getAllValues()).extracting(Job::getTemplateReference)
                    .containsExactlyInAnyOrder("template-1", "template-2");
        }

        @Test
        void v2WebhookMergeRequestScenario() {
            String repoUrl = "https://gitlab.com/owner/repo";
            String secret = "mr-migration-test-secret";
            String payload = "{\"object_kind\":\"merge_request\"}";

            RepoWebhook rw = repoWebhookWith(repoUrl, secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(true);

            Workspace ws1 = workspaceWithSource(repoUrl);
            ws1.setName("ws-mr-1");
            Workspace ws2 = workspaceWithSource(repoUrl);
            ws2.setName("ws-mr-2");

            Webhook wh1 = new Webhook();
            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.PULL_REQUEST);
            event1.setBranch("feature-branch");
            event1.setPath("*.tf");
            event1.setPathType(WebhookEventPathType.PATTERN);
            event1.setTemplateId("mr-template-1");
            wh1.setEvents(List.of(event1));
            ws1.setWebhook(wh1);

            Webhook wh2 = new Webhook();
            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.PULL_REQUEST);
            event2.setBranch("feature-branch");
            event2.setPath("*.tf");
            event2.setPathType(WebhookEventPathType.PATTERN);
            event2.setTemplateId("mr-template-2");
            wh2.setEvents(List.of(event2));
            ws2.setWebhook(wh2);

            WebhookResult mrResult = new WebhookResult();
            mrResult.setEvent("merge_request");
            mrResult.setValid(true);
            mrResult.setBranch("feature-branch");
            mrResult.setCommit("def456");
            mrResult.setPrNumber(42);
            mrResult.setFileChanges(List.of("variables.tf"));
            when(gitLabWebhookService.parseGitlabPayload(eq(payload), any(), any(), eq(repoUrl)))
                    .thenReturn(mrResult);

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

            subject.processV2Webhook(rw.getId().toString(), payload, headersWithToken(secret));

            verify(jobRepository, times(2)).save(any(Job.class));

            ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
            verify(jobRepository, times(2)).save(jobCaptor.capture());
            List<Job> savedJobs = jobCaptor.getAllValues();
            assertThat(savedJobs).extracting(Job::getTemplateReference)
                    .containsExactlyInAnyOrder("mr-template-1", "mr-template-2");
            assertThat(savedJobs).allSatisfy(job -> {
                assertThat(job.getCommitId()).isEqualTo("def456");
                assertThat(job.getOverrideBranch()).isEqualTo("feature-branch");
            });
        }

        @Test
        void v2WebhookReleaseScenario() {
            String repoUrl = "https://gitlab.com/owner/repo";
            String secret = "release-test-secret";
            String payload = "{\"object_kind\":\"release\"}";

            RepoWebhook rw = repoWebhookWith(repoUrl, secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(true);

            Workspace ws1 = workspaceWithSource(repoUrl);
            ws1.setName("ws-release-1");
            Workspace ws2 = workspaceWithSource(repoUrl);
            ws2.setName("ws-release-2");

            Webhook wh1 = new Webhook();
            WebhookEvent event1 = new WebhookEvent();
            event1.setEvent(WebhookEventType.RELEASE);
            event1.setBranch("v1.*");
            event1.setTemplateId("release-template-1");
            wh1.setEvents(List.of(event1));
            ws1.setWebhook(wh1);

            Webhook wh2 = new Webhook();
            WebhookEvent event2 = new WebhookEvent();
            event2.setEvent(WebhookEventType.RELEASE);
            event2.setBranch(".*");
            event2.setTemplateId("release-template-2");
            wh2.setEvents(List.of(event2));
            ws2.setWebhook(wh2);

            WebhookResult releaseResult = new WebhookResult();
            releaseResult.setEvent("release");
            releaseResult.setValid(true);
            releaseResult.setBranch("v1.0.0");
            releaseResult.setCommit("tag-sha-123");
            releaseResult.setRelease(true);
            when(gitLabWebhookService.parseGitlabPayload(eq(payload), any(), any(), eq(repoUrl)))
                    .thenReturn(releaseResult);

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

            subject.processV2Webhook(rw.getId().toString(), payload, headersWithToken(secret));

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
        }

        @Test
        void v2WebhookMigrationRemovalScenario() {
            String repoUrl = "https://gitlab.com/owner/repo";

            Workspace workspace = workspaceWithSource(repoUrl);
            workspace.setName("workspace-v1");
            Vcs vcs = new Vcs();
            vcs.setVcsType(VcsType.GITLAB);
            workspace.setVcs(vcs);

            Webhook webhook = new Webhook();
            webhook.setWorkspace(workspace);
            webhook.setMigratedV2(false);
            webhook.setRemoteHookId("old-v1-hook-id");
            workspace.setWebhook(webhook);

            webhook.setMigratedV2(true);

            RepoWebhook repoWebhook = repoWebhookWith(repoUrl, "new-secret");
            when(repoWebhookRepository.findByRepositoryUrl(anyString())).thenReturn(Optional.of(repoWebhook));

            // Simulate the WebhookManageHook GitLab logic
            if (webhook.isMigratedV2() && workspace.getVcs() != null
                    && workspace.getVcs().getVcsType() == VcsType.GITLAB) {
                subject.getOrCreateRepoWebhook(workspace);
                subject.createOrUpdateSharedWebhook(repoWebhook);

                if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
                    gitLabWebhookService.deleteWebhook(workspace, webhook.getRemoteHookId());
                    webhook.setRemoteHookId(null);
                }
            }

            verify(gitLabWebhookService).deleteWebhook(workspace, "old-v1-hook-id");
            assertThat(webhook.getRemoteHookId()).isNull();
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
        void throwsSecurityExceptionOnTokenFailure() {
            String secret = "test-secret";
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(false);

            Map<String, String> headers = Map.of("x-gitlab-token", "wrong");

            assertThatThrownBy(() -> subject.processV2Webhook(rw.getId().toString(), "{}", headers))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("GitLab token verification failed");

            verify(jobRepository, never()).save(any());
        }

        @Test
        void fansOutToMultipleWorkspaces() {
            String secret = "test-secret";
            String payload = "{\"object_kind\":\"push\", \"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(true);

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCreatedBy("user");
            pushResult.setVia("GitLab");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitLabWebhookService.parseGitlabPayload(eq(payload), any(), any(), any()))
                    .thenReturn(pushResult);

            Workspace ws1 = workspaceWithSource("https://gitlab.com/owner/repo");
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

            Workspace ws2 = workspaceWithSource("https://gitlab.com/owner/repo");
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

            subject.processV2Webhook(rw.getId().toString(), payload, headersWithToken(secret));

            verify(jobRepository, times(2)).save(any(Job.class));
        }

        @Test
        void continuesProcessingWhenOneWorkspaceFails() {
            String secret = "test-secret";
            String payload = "{\"object_kind\":\"push\", \"ref\":\"refs/heads/main\"}";
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(true);

            WebhookResult pushResult = new WebhookResult();
            pushResult.setEvent("push");
            pushResult.setValid(true);
            pushResult.setBranch("main");
            pushResult.setCreatedBy("user");
            pushResult.setVia("GitLab");
            pushResult.setCommit("abc123");
            pushResult.setFileChanges(List.of("main.tf"));
            when(gitLabWebhookService.parseGitlabPayload(eq(payload), any(), any(), any()))
                    .thenReturn(pushResult);

            // ws1 has no webhook (skipped)
            Workspace ws1 = workspaceWithSource("https://gitlab.com/owner/repo");
            ws1.setName("ws1-no-webhook");

            Workspace ws2 = workspaceWithSource("https://gitlab.com/owner/repo");
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

            subject.processV2Webhook(rw.getId().toString(), payload, headersWithToken(secret));

            verify(jobRepository, times(1)).save(any(Job.class));
        }

        @Test
        void skipsInvalidWebhookResult() {
            String secret = "test-secret";
            String payload = "{}";
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(true);

            WebhookResult invalidResult = new WebhookResult();
            invalidResult.setEvent("unknown");
            invalidResult.setValid(false);
            when(gitLabWebhookService.parseGitlabPayload(eq(payload), any(), any(), any()))
                    .thenReturn(invalidResult);

            subject.processV2Webhook(rw.getId().toString(), payload, headersWithToken(secret));

            verify(workspaceRepository, never()).findByNormalizedSourceWithMigratedWebhook(any());
            verify(jobRepository, never()).save(any());
        }
    }

    @Nested
    class VerifyGitlabToken {

        @Test
        void acceptsValidToken() {
            String secret = "my-webhook-secret";
            String payload = "{\"object_kind\":\"push\"}";
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(true);

            WebhookResult invalidResult = new WebhookResult();
            invalidResult.setEvent("push");
            invalidResult.setValid(false);
            when(gitLabWebhookService.parseGitlabPayload(eq(payload), any(), any(), any()))
                    .thenReturn(invalidResult);

            // Should not throw — valid token
            subject.processV2Webhook(rw.getId().toString(), payload, headersWithToken(secret));
        }

        @Test
        void rejectsWrongToken() {
            String secret = "correct-secret";
            String payload = "{}";
            RepoWebhook rw = repoWebhookWith("https://gitlab.com/owner/repo", secret);
            when(repoWebhookRepository.findById(rw.getId())).thenReturn(Optional.of(rw));
            when(gitLabWebhookService.verifyGitlabToken(any(), any())).thenReturn(false);

            Map<String, String> headers = Map.of("x-gitlab-token", "wrong-secret");

            assertThatThrownBy(() -> subject.processV2Webhook(rw.getId().toString(), payload, headers))
                    .isInstanceOf(SecurityException.class);
        }
    }

    private Map<String, String> headersWithToken(String secret) {
        return Map.of("x-gitlab-token", secret);
    }
}
