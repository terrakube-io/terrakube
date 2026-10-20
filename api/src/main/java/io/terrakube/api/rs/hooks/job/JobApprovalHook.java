package io.terrakube.api.rs.hooks.job;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.policy.PolicyApprovalGuard;
import io.terrakube.api.plugin.policy.PolicyBlocksApprovalException;
import io.terrakube.api.plugin.security.audit.JobApprovalService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobApprovalHook implements LifeCycleHook<Job> {
    private final JobApprovalService jobApprovalService;
    private final PolicyApprovalGuard policyApprovalGuard;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Job job, RequestScope scope,
            Optional<ChangeSpec> changes) {
        if (phase != TransactionPhase.PRECOMMIT) {
            return;
        }
        if (operation == Operation.CREATE) {
            // A job that is created as approved would skip the approval, and with it the policy checks.
            if (JobStatus.approved.equals(job.getStatus())) {
                throw new PolicyBlocksApprovalException(
                        "A job cannot be created as approved: it is approved while it waits for approval.");
            }
            return;
        }
        if (operation != Operation.UPDATE) {
            return;
        }
        Optional<ChangeSpec> approval = changes
                .filter(change -> "status".equals(change.getFieldName()))
                .filter(change -> JobStatus.approved.equals(change.getModified()));
        if (approval.isEmpty()) {
            return;
        }
        // The approve permission of a team does not look at where the job is, so a job that is still
        // planning could be approved past its policy checks. Only a job that waits can be approved.
        Object original = approval.get().getOriginal();
        if (!JobStatus.waitingApproval.equals(original)) {
            throw new PolicyBlocksApprovalException(String.format(
                    "Job %d cannot be approved from status '%s': only a job that is waiting for approval can be approved.",
                    job.getId(), original));
        }
        // Before anything is recorded: a job whose policy evaluations still block it stays waiting.
        Optional<String> blockingReason = policyApprovalGuard.blockingReason(job);
        if (blockingReason.isPresent()) {
            throw new PolicyBlocksApprovalException(blockingReason.get());
        }
        jobApprovalService.recordApproval(job, (JwtAuthenticationToken) scope.getUser().getPrincipal());
    }
}
