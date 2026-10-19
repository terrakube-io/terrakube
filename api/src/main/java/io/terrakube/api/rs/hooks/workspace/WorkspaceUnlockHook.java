package io.terrakube.api.rs.hooks.workspace;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs when {@code Workspace.locked} goes from true to false (#3728).
 *
 * <p>Before the commit it clears the lock description, so a description left by a PR apply lock
 * (see {@code JobRepository.JOB_NOT_LOCKED_OUT}) cannot outlive the lock and exempt a PR's
 * auto-apply run from a later, manual lock.
 *
 * <p>After the commit it wakes the queue head: jobs in a locked workspace are left out of the
 * global FIFO queue, so they become eligible again now instead of on the next 30s trigger. The
 * wake is best effort and must never fail the request, whose transaction has already committed.
 */
@AllArgsConstructor
@Slf4j
public class WorkspaceUnlockHook implements LifeCycleHook<Workspace> {

    ScheduleJobService scheduleJobService;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Workspace workspace, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        boolean unlocked = changeSpec
                .filter(change -> "locked".equals(change.getFieldName()))
                .map(change -> Boolean.TRUE.equals(change.getOriginal()) && Boolean.FALSE.equals(change.getModified()))
                .orElse(false);
        if (!unlocked) {
            return;
        }
        if (transactionPhase == LifeCycleHookBinding.TransactionPhase.PRECOMMIT) {
            workspace.setLockDescription(null);
            return;
        }
        try {
            log.info("Workspace {} unlocked, waking the job queue", workspace.getName());
            scheduleJobService.wakeQueueHead();
        } catch (RuntimeException e) {
            log.error("Failed to wake the job queue after unlocking workspace {}", workspace.getName(), e);
        }
    }
}
