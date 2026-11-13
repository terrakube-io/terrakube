package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.execution.JobRunRights;
import io.terrakube.api.plugin.execution.JobRightForbiddenException;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.rs.job.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Rights on top of the right to plan that lets a user create a job, for a run that changes infrastructure with no
 * approval in front of it:
 * <ul>
 *   <li>a destroy needs the destroy right;</li>
 *   <li>an apply needs the right to approve, on a workspace that asks for it ({@code requireApproveToApply}).</li>
 * </ul>
 * A run that waits for an approval can still be queued by a team that only plans, because someone who may approve
 * has to let it through. Runs before the flush, so a refused job is never saved. Covers REST, GraphQL and the UI.
 */
@Component
@RequiredArgsConstructor
public class JobRunRightsHook implements LifeCycleHook<Job> {
    private final TclService tclService;
    private final JobRunRights rights;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Job job, RequestScope scope,
                        Optional<ChangeSpec> changes) {
        if (operation != Operation.CREATE || phase != TransactionPhase.PREFLUSH) {
            return;
        }
        String template = job.getTemplateReference();
        if (tclService.templateDestroys(template) == TclService.Destroys.WITHOUT_APPROVAL
                && !rights.canDestroy(scope.getUser(), job.getWorkspace())) {
            throw new JobRightForbiddenException(JobRunRights.destroyRefusal(job.getWorkspace()));
        }
        if (job.getWorkspace() != null && job.getWorkspace().isRequireApproveToApply()
                && tclService.appliesWithoutApproval(template)
                && !rights.canApprove(scope.getUser(), job.getWorkspace())) {
            throw new JobRightForbiddenException(JobRunRights.applyRefusal(job.getWorkspace()));
        }
    }
}
