package io.terrakube.api.plugin.security.audit;

import java.util.Date;

import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class JobApprovalService {
    public void recordApproval(Job job, JwtAuthenticationToken user) {
        String email = user.getToken().getClaimAsString("email");
        String approver = email == null || email.isBlank() ? user.getName() : email;
        Date approvedAt = new Date();
        job.setApprovedBy(approver);
        job.setApprovedAt(approvedAt);
        // Both callers run inside the transaction that persists the approval.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Plan approved: organization={} workspace={} job={} approvedBy={} approvedAt={}",
                        job.getOrganization().getId(), job.getWorkspace().getId(), job.getId(),
                        approver, approvedAt.toInstant());
            }
        });
    }
}
