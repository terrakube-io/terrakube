package io.terrakube.api.rs.hooks.job;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
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

    @Override
    public void execute(Operation operation, TransactionPhase phase, Job job, RequestScope scope,
            Optional<ChangeSpec> changes) {
        if (operation == Operation.UPDATE && phase == TransactionPhase.PRECOMMIT
                && changes.filter(change -> "status".equals(change.getFieldName()))
                        .filter(change -> JobStatus.waitingApproval.equals(change.getOriginal()))
                        .filter(change -> JobStatus.approved.equals(change.getModified())).isPresent()) {
            jobApprovalService.recordApproval(job, (JwtAuthenticationToken) scope.getUser().getPrincipal());
        }
    }
}
