package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.execution.JobRightForbiddenException;
import io.terrakube.api.plugin.execution.JobRunRights;
import io.terrakube.api.plugin.policy.PolicyApprovalGuard;
import io.terrakube.api.plugin.policy.PolicyBlocksApprovalException;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.security.audit.JobApprovalService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobApprovalHookTest {

    @Mock
    JobApprovalService jobApprovalService;
    @Mock
    PolicyApprovalGuard policyApprovalGuard;
    @Mock
    TclService tclService;
    @Mock
    JobRunRights rights;
    @Mock
    RequestScope scope;

    private JobApprovalHook hook;
    private Job job;

    @BeforeEach
    void setUp() {
        hook = new JobApprovalHook(jobApprovalService, policyApprovalGuard, tclService, rights);
        job = new Job();
        job.setId(7);
        org.mockito.Mockito.lenient().when(tclService.templateDestroys(any())).thenReturn(TclService.Destroys.NO);
    }

    private Optional<ChangeSpec> statusChange(JobStatus from, JobStatus to) {
        return Optional.of(new ChangeSpec(null, "status", from, to));
    }

    private JwtAuthenticationToken principal() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user").build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        when(scope.getUser()).thenReturn(new User(token));
        return token;
    }

    @Test
    void recordsTheApprovalWhenNothingBlocksIt() {
        JwtAuthenticationToken token = principal();
        when(policyApprovalGuard.blockingReason(job)).thenReturn(Optional.empty());

        hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, scope,
                statusChange(JobStatus.waitingApproval, JobStatus.approved));

        verify(jobApprovalService).recordApproval(job, token);
    }

    @Test
    void refusesToApproveARunThatDestroysWhenTheTeamMayNeverDestroy() {
        principal();
        io.terrakube.api.rs.workspace.Workspace workspace = new io.terrakube.api.rs.workspace.Workspace();
        workspace.setName("prod");
        job.setWorkspace(workspace);
        when(tclService.templateDestroys(any())).thenReturn(TclService.Destroys.AFTER_APPROVAL);
        when(rights.canDestroy(any(), any())).thenReturn(false);

        JobRightForbiddenException thrown = assertThrows(JobRightForbiddenException.class,
                () -> hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, scope,
                        statusChange(JobStatus.waitingApproval, JobStatus.approved)));

        assertEquals(403, thrown.getStatus());
        verify(jobApprovalService, never()).recordApproval(any(), any());
        verifyNoInteractions(policyApprovalGuard);
    }

    @Test
    void approvesARunThatDestroysWhenTheTeamMayDestroy() {
        JwtAuthenticationToken token = principal();
        when(tclService.templateDestroys(any())).thenReturn(TclService.Destroys.AFTER_APPROVAL);
        when(rights.canDestroy(any(), any())).thenReturn(true);
        when(policyApprovalGuard.blockingReason(job)).thenReturn(Optional.empty());

        hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, scope,
                statusChange(JobStatus.waitingApproval, JobStatus.approved));

        verify(jobApprovalService).recordApproval(job, token);
    }

    @Test
    void refusesTheApprovalWithAConflictWhilePolicyBlocksIt() {
        when(policyApprovalGuard.blockingReason(job)).thenReturn(Optional.of("Job 7 cannot be approved: reason"));

        PolicyBlocksApprovalException thrown = assertThrows(PolicyBlocksApprovalException.class,
                () -> hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, scope,
                        statusChange(JobStatus.waitingApproval, JobStatus.approved)));

        assertEquals(409, thrown.getStatus());
        verify(jobApprovalService, never()).recordApproval(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"pending", "queue", "running", "completed", "failed", "approved"})
    void refusesAnApprovalFromAnyStatusButWaitingForApproval(JobStatus from) {
        PolicyBlocksApprovalException thrown = assertThrows(PolicyBlocksApprovalException.class,
                () -> hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, scope,
                        statusChange(from, JobStatus.approved)));

        assertEquals(409, thrown.getStatus());
        verify(jobApprovalService, never()).recordApproval(any(), any());
        verifyNoInteractions(policyApprovalGuard);
    }

    @Test
    void refusesAJobThatIsCreatedAsApproved() {
        job.setStatus(JobStatus.approved);

        PolicyBlocksApprovalException thrown = assertThrows(PolicyBlocksApprovalException.class,
                () -> hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, job, scope, Optional.empty()));

        assertEquals(409, thrown.getStatus());
        verifyNoInteractions(policyApprovalGuard, jobApprovalService);
    }

    @Test
    void aJobCreatedInAnotherStatusIsLeftAlone() {
        job.setStatus(JobStatus.pending);

        hook.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, job, scope, Optional.empty());

        verifyNoInteractions(policyApprovalGuard, jobApprovalService);
    }

    @Test
    void otherTransitionsAreNotChecked() {
        hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, scope,
                statusChange(JobStatus.waitingApproval, JobStatus.rejected));
        hook.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, scope,
                statusChange(JobStatus.pending, JobStatus.running));

        verifyNoInteractions(policyApprovalGuard, jobApprovalService);
    }
}
