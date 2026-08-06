package io.terrakube.api.plugin.scheduler.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;

import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class RepoWebhookSyncJobTest {

    @Mock
    RepoWebhookRepository repoWebhookRepository;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    RepoWebhookService repoWebhookService;

    @InjectMocks
    RepoWebhookSyncJob subject;

    @Test
    void noWorkspacesLeftAndRepoWebhookExists_cleansUpOrphan() throws Exception {
        String repositoryUrl = "https://github.com/owner/repo";
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setRepositoryUrl(repositoryUrl);

        doReturn(List.of()).when(workspaceRepository).findByNormalizedSourceWithMigratedWebhook(repositoryUrl);
        doReturn(Optional.of(repoWebhook)).when(repoWebhookRepository).findByRepositoryUrl(repositoryUrl);

        subject.execute(jobExecutionContext(repositoryUrl, null));

        verify(repoWebhookService).cleanupIfOrphan(repoWebhook);
        verify(repoWebhookService, never()).getOrCreateRepoWebhook(any());
        verify(repoWebhookService, never()).createOrUpdateSharedWebhook(any());
    }

    @Test
    void noWorkspacesLeftAndNoRepoWebhookRow_doesNothing() throws Exception {
        String repositoryUrl = "https://github.com/owner/repo";

        doReturn(List.of()).when(workspaceRepository).findByNormalizedSourceWithMigratedWebhook(repositoryUrl);
        doReturn(Optional.empty()).when(repoWebhookRepository).findByRepositoryUrl(repositoryUrl);

        subject.execute(jobExecutionContext(repositoryUrl, null));

        verify(repoWebhookService, never()).cleanupIfOrphan(any());
        verify(repoWebhookService, never()).getOrCreateRepoWebhook(any());
        verify(repoWebhookService, never()).createOrUpdateSharedWebhook(any());
    }

    @Test
    void workspacesPresent_syncsUsingTriggeringWorkspaceAsSeed() throws Exception {
        String repositoryUrl = "https://github.com/owner/repo";
        UUID triggeringId = UUID.randomUUID();
        Workspace triggering = workspace(triggeringId);
        Workspace other = workspace(UUID.randomUUID());
        RepoWebhook repoWebhook = new RepoWebhook();

        doReturn(List.of(other, triggering)).when(workspaceRepository)
                .findByNormalizedSourceWithMigratedWebhook(repositoryUrl);
        doReturn(repoWebhook).when(repoWebhookService).getOrCreateRepoWebhook(triggering);

        subject.execute(jobExecutionContext(repositoryUrl, triggeringId.toString()));

        verify(repoWebhookService).getOrCreateRepoWebhook(triggering);
        verify(repoWebhookService).createOrUpdateSharedWebhook(repoWebhook);
        verify(repoWebhookRepository, never()).findByRepositoryUrl(any());
    }

    @Test
    void workspacesPresentButTriggeringWorkspaceNoLongerInSet_fallsBackToFirstWorkspace() throws Exception {
        String repositoryUrl = "https://github.com/owner/repo";
        Workspace first = workspace(UUID.randomUUID());
        Workspace second = workspace(UUID.randomUUID());
        RepoWebhook repoWebhook = new RepoWebhook();

        doReturn(List.of(first, second)).when(workspaceRepository)
                .findByNormalizedSourceWithMigratedWebhook(repositoryUrl);
        doReturn(repoWebhook).when(repoWebhookService).getOrCreateRepoWebhook(first);

        // The workspace that scheduled the sync (e.g. was deleted since) is
        // no longer in the returned set — falls back to the first remaining
        // workspace as the seed.
        subject.execute(jobExecutionContext(repositoryUrl, UUID.randomUUID().toString()));

        verify(repoWebhookService).getOrCreateRepoWebhook(first);
        verify(repoWebhookService).createOrUpdateSharedWebhook(repoWebhook);
    }

    @Test
    void nullOrBlankWorkspaceId_fallsBackToFirstWorkspace() throws Exception {
        String repositoryUrl = "https://github.com/owner/repo";
        Workspace first = workspace(UUID.randomUUID());
        RepoWebhook repoWebhook = new RepoWebhook();

        doReturn(List.of(first)).when(workspaceRepository)
                .findByNormalizedSourceWithMigratedWebhook(repositoryUrl);
        doReturn(repoWebhook).when(repoWebhookService).getOrCreateRepoWebhook(first);

        subject.execute(jobExecutionContext(repositoryUrl, null));

        verify(repoWebhookService).getOrCreateRepoWebhook(first);
        verify(repoWebhookService).createOrUpdateSharedWebhook(repoWebhook);
    }

    private Workspace workspace(UUID id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        return workspace;
    }

    private JobExecutionContext jobExecutionContext(String repositoryUrl, String workspaceId) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(RepoWebhookSyncScheduler.DATA_KEY_REPOSITORY_URL, repositoryUrl);
        jobDataMap.put(RepoWebhookSyncScheduler.DATA_KEY_WORKSPACE_ID, workspaceId);

        JobDetail jobDetail = mock(JobDetail.class);
        doReturn(jobDataMap).when(jobDetail).getJobDataMap();

        JobExecutionContext jobExecutionContext = mock(JobExecutionContext.class);
        doReturn(jobDetail).when(jobExecutionContext).getJobDetail();
        return jobExecutionContext;
    }
}
