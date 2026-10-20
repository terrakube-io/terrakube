package io.terrakube.api.rs.hooks.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.rs.workspace.Workspace;

class WorkspaceUnlockHookTest {

    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final WorkspaceUnlockHook hook = new WorkspaceUnlockHook(scheduleJobService);

    private Workspace run(TransactionPhase phase, String field, Object original, Object modified) {
        Workspace workspace = new Workspace();
        workspace.setLockDescription("Locked by PR #7 apply");
        hook.execute(Operation.UPDATE, phase, workspace, null,
                Optional.of(new ChangeSpec(null, field, original, modified)));
        return workspace;
    }

    @Test
    void unlockingWakesTheQueueAfterCommit() {
        run(TransactionPhase.POSTCOMMIT, "locked", true, false);

        verify(scheduleJobService).wakeQueueHead();
    }

    @Test
    void lockingDoesNotWakeTheQueue() {
        run(TransactionPhase.POSTCOMMIT, "locked", false, true);

        verify(scheduleJobService, never()).wakeQueueHead();
    }

    @Test
    void anotherFieldChangeDoesNotWakeTheQueue() {
        run(TransactionPhase.POSTCOMMIT, "name", true, false);

        verify(scheduleJobService, never()).wakeQueueHead();
    }

    @Test
    void noChangeSpecDoesNotWakeTheQueue() {
        hook.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, new Workspace(), null, Optional.empty());

        verify(scheduleJobService, never()).wakeQueueHead();
    }

    @Test
    void aFailingWakeNeverFailsTheAlreadyCommittedRequest() {
        doThrow(new IllegalStateException("boom")).when(scheduleJobService).wakeQueueHead();

        assertThatCode(() -> run(TransactionPhase.POSTCOMMIT, "locked", true, false)).doesNotThrowAnyException();
    }

    @Test
    void unlockingClearsTheLockDescriptionBeforeCommitAndDoesNotWake() {
        Workspace workspace = run(TransactionPhase.PRECOMMIT, "locked", true, false);

        assertThat(workspace.getLockDescription()).isNull();
        verify(scheduleJobService, never()).wakeQueueHead();
    }

    @Test
    void lockingKeepsTheLockDescription() {
        Workspace workspace = run(TransactionPhase.PRECOMMIT, "locked", false, true);

        assertThat(workspace.getLockDescription()).isEqualTo("Locked by PR #7 apply");
    }
}
