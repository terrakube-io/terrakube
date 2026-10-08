package io.terrakube.api.rs.hooks.vcs;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;

import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;

@ExtendWith(MockitoExtension.class)
class VcsManageHookTest {

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    @InjectMocks
    VcsManageHook subject;

    private Workspace workspace(String source, Vcs vcs) {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setSource(source);
        workspace.setVcs(vcs);
        return workspace;
    }

    private Vcs vcs() {
        Vcs vcs = new Vcs();
        vcs.setId(UUID.randomUUID());
        return vcs;
    }

    @Test
    void updateSchedulesOneSyncPerRepositoryOfThisVcsMigratedWorkspaces() {
        Vcs vcs = vcs();
        Workspace first = workspace("https://github.com/owner/repo.git", vcs);
        Workspace sameRepo = workspace("https://github.com/Owner/Repo", vcs);
        Workspace otherRepo = workspace("https://github.com/owner/other", vcs);
        Workspace noSource = workspace(null, vcs);
        when(workspaceRepository.findMigratedByVcsId(vcs.getId()))
                .thenReturn(List.of(first, sameRepo, otherRepo, noSource));

        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, vcs, null, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync("https://github.com/owner/repo", first.getId().toString());
        verify(repoWebhookSyncScheduler).scheduleSync("https://github.com/owner/other", otherRepo.getId().toString());
        verifyNoMoreInteractions(repoWebhookSyncScheduler);
    }

    @Test
    void otherPhasesDoNothing() {
        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, vcs(), null, Optional.empty());

        verifyNoInteractions(workspaceRepository, repoWebhookSyncScheduler);
    }
}
