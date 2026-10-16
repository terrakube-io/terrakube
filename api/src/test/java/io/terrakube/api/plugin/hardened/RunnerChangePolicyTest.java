package io.terrakube.api.plugin.hardened;

import com.yahoo.elide.core.security.ChangeSpec;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunnerChangePolicyTest {

    RunnerJobGuard guard = mock(RunnerJobGuard.class);
    RunnerChangePolicy policy = new RunnerChangePolicy(guard, "api.example");
    Job job = new Job();
    Step step = new Step();
    Map<String, Object> claims = Map.of("iss", RunnerToken.ISSUER, "organizationId", "o", "workspaceId", "w", "jobId", "7", "stepId", "s");

    @BeforeEach
    void setup() {
        job.setId(7);
        step.setId(UUID.randomUUID());
        when(guard.stepStartingOrRunning(any())).thenReturn(true);
        when(guard.stepReporting(any())).thenReturn(true);
        when(guard.jobActive(any())).thenReturn(true);
    }

    private Optional<ChangeSpec> change(String field, Object original, Object modified) {
        ChangeSpec spec = mock(ChangeSpec.class);
        when(spec.getFieldName()).thenReturn(field);
        when(spec.getOriginal()).thenReturn(original);
        when(spec.getModified()).thenReturn(modified);
        return Optional.of(spec);
    }

    @Test
    void runnersCanOnlyReportProgressOnTheirJob() {
        assertThat(policy.canRead(claims)).isTrue();
        assertThat(policy.canChangeJob(claims, job, Optional.empty())).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("status", JobStatus.running, JobStatus.completed))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("status", JobStatus.running, JobStatus.waitingApproval))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("commitId", null, "abc"))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("commitId", "abc", "abc"))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("planChanges", true, false))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("output", null, " Step s completed\n"))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("output", " Step p completed\n", " Step p completed\n Step s completed\n"))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("terraformPlan", "x", ""))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("terraformPlan", null, "https://api.example/tfstate/v1/organization/o/workspace/w/jobId/7/step/s/terraform.tfstate"))).isTrue();
        assertThat(policy.canChangeJob(claims, job, change("terraformPlan", "x", null))).isTrue();
    }

    @Test
    void runnersCannotApproveRerouteOrRetarget() {
        assertThat(policy.canChangeJob(claims, job, change("status", JobStatus.waitingApproval, JobStatus.approved))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("status", JobStatus.waitingApproval, JobStatus.running))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("status", JobStatus.running, "approved"))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("commitId", "abc", "def"))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("output", null, "https://evil.example/x"))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("output", " Step p completed\n", " Step s completed\n"))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("output", null, " Step other completed\n"))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("output", null, null))).isFalse();
        assertThat(policy.canChangeJob(claims, job, change("terraformPlan", null, "https://api.example/tfstate/v1/organization/o/workspace/w/jobId/8/step/s/terraform.tfstate"))).isFalse();
        for (String field : new String[]{"autoApply", "approvalTeam", "overrideBranch", "overrideSource", "templateReference", "deleted", "comments"}) {
            assertThat(policy.canChangeJob(claims, job, change(field, null, "x"))).as(field).isFalse();
        }
        when(guard.stepReporting(any())).thenReturn(false);
        assertThat(policy.canChangeJob(claims, job, change("status", JobStatus.running, JobStatus.completed))).isFalse();
        when(guard.stepStartingOrRunning(any())).thenReturn(false);
        assertThat(policy.canChangeStep(claims, step, Optional.empty())).isFalse();
    }

    @Test
    void runnersCanOnlyUpdateTheirStepStatusAndOutput() {
        assertThat(policy.canChangeStep(claims, step, Optional.empty())).isTrue();
        assertThat(policy.canChangeStep(claims, step, change("status", JobStatus.pending, JobStatus.running))).isTrue();
        assertThat(policy.canChangeStep(claims, step, change("status", JobStatus.running, JobStatus.completed))).isTrue();
        assertThat(policy.canChangeStep(claims, step, change("status", JobStatus.running, JobStatus.approved))).isFalse();
        assertThat(policy.canChangeStep(claims, step, change("output", null,
                "https://api.example/tfoutput/v1/organization/o/job/7/step/" + step.getId()))).isTrue();
        assertThat(policy.canChangeStep(claims, step, change("output", null, null))).isTrue();
        assertThat(policy.canChangeStep(claims, step, change("output", null, "https://evil.example/steal"))).isFalse();
        assertThat(policy.canChangeStep(claims, step, change("name", null, "x"))).isFalse();
    }
}
