package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.execution.JobBranchPolicyException;
import io.terrakube.api.plugin.execution.WorkspaceBranchPolicy;
import io.terrakube.api.rs.job.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Enforces the workspace "restrict apply to the configured branch" setting when a job is created through the
 * API (REST and GraphQL, so also the UI). It runs before the flush, so a refused job is never saved.
 */
@Component
@RequiredArgsConstructor
public class JobBranchPolicyHook implements LifeCycleHook<Job> {
    private final WorkspaceBranchPolicy policy;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Job job, RequestScope scope,
                        Optional<ChangeSpec> changes) {
        if (operation == Operation.CREATE && phase == TransactionPhase.PREFLUSH) {
            Optional<String> violation = policy.violation(job.getWorkspace(), job.getOverrideBranch(),
                    job.getCommitId(), job.getTemplateReference());
            if (violation.isPresent()) {
                throw new JobBranchPolicyException(violation.get());
            }
        }
    }
}
