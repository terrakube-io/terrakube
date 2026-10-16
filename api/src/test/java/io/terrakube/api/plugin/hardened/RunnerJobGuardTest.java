package io.terrakube.api.plugin.hardened;

import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunnerJobGuardTest {

    JobRepository jobs = mock(JobRepository.class);
    StepRepository steps = mock(StepRepository.class);
    RunnerJobGuard guard = new RunnerJobGuard(jobs, steps);
    Organization organization = new Organization();
    Workspace workspace = new Workspace();
    Job job = new Job();
    Step step = new Step();

    @BeforeEach
    void setup() {
        organization.setId(UUID.randomUUID());
        workspace.setId(UUID.randomUUID());
        job.setId(7);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setStatus(JobStatus.running);
        step.setId(UUID.randomUUID());
        step.setJob(job);
        step.setStatus(JobStatus.running);
        when(jobs.findById(7)).thenReturn(Optional.of(job));
        when(steps.findById(step.getId())).thenReturn(Optional.of(step));
    }

    private Map<String, Object> claims(String flow) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", RunnerToken.ISSUER);
        claims.put(RunnerToken.CLAIM_ORGANIZATION_ID, organization.getId().toString());
        claims.put(RunnerToken.CLAIM_WORKSPACE_ID, workspace.getId().toString());
        claims.put(RunnerToken.CLAIM_JOB_ID, "7");
        claims.put(RunnerToken.CLAIM_STEP_ID, step.getId().toString());
        claims.put(RunnerToken.CLAIM_FLOW, flow);
        return claims;
    }

    @Test
    void runningApplyStepsMayWriteState() {
        assertThat(guard.jobActive(claims("terraformApply"))).isTrue();
        assertThat(guard.stepRunning(claims("terraformApply"))).isTrue();
        assertThat(guard.canWriteState(claims("terraformApply"))).isTrue();
        assertThat(guard.canWriteState(claims("terraformDestroy"))).isTrue();
        assertThat(guard.canWriteState(claims("customScripts"))).isTrue();
        assertThat(guard.canWriteState(claims("terraformPlan"))).isFalse();
        assertThat(guard.canWriteState(claims(null))).isFalse();
        assertThat(guard.isStepRunning("7", step.getId().toString())).isTrue();
    }

    @Test
    void aFinishedStepMayReportUntilAnotherStepRuns() {
        Step next = new Step();
        next.setId(UUID.randomUUID());
        next.setJob(job);
        next.setStatus(JobStatus.pending);
        when(steps.findByJobId(7)).thenReturn(java.util.List.of(step, next));

        assertThat(guard.stepReporting(claims("terraformPlan"))).isTrue();
        step.setStatus(JobStatus.completed);
        assertThat(guard.stepReporting(claims("terraformPlan"))).isTrue();
        step.setStatus(JobStatus.failed);
        assertThat(guard.stepReporting(claims("terraformPlan"))).isTrue();
        next.setStatus(JobStatus.running);
        assertThat(guard.stepReporting(claims("terraformPlan"))).isFalse();
        next.setStatus(JobStatus.pending);
        step.setStatus(JobStatus.cancelled);
        assertThat(guard.stepReporting(claims("terraformPlan"))).isFalse();
        step.setStatus(JobStatus.completed);
        job.setStatus(JobStatus.completed);
        assertThat(guard.stepReporting(claims("terraformPlan"))).isFalse();
        Map<String, Object> badStep = claims("terraformPlan");
        badStep.put(RunnerToken.CLAIM_STEP_ID, "x");
        assertThat(guard.stepReporting(badStep)).isFalse();
    }

    @Test
    void finishedOrForeignJobsAreInactive() {
        step.setStatus(JobStatus.pending);
        assertThat(guard.stepRunning(claims("terraformApply"))).isFalse();
        assertThat(guard.stepStartingOrRunning(claims("terraformApply"))).isTrue();
        step.setStatus(JobStatus.completed);
        assertThat(guard.stepStartingOrRunning(claims("terraformApply"))).isFalse();

        job.setStatus(JobStatus.completed);
        assertThat(guard.jobActive(claims("terraformApply"))).isFalse();
        assertThat(guard.isStepRunning("7", step.getId().toString())).isFalse();
        job.setStatus(JobStatus.running);

        Map<String, Object> otherWorkspace = claims("terraformApply");
        otherWorkspace.put(RunnerToken.CLAIM_WORKSPACE_ID, UUID.randomUUID().toString());
        assertThat(guard.jobActive(otherWorkspace)).isFalse();
        Map<String, Object> otherOrganization = claims("terraformApply");
        otherOrganization.put(RunnerToken.CLAIM_ORGANIZATION_ID, UUID.randomUUID().toString());
        assertThat(guard.jobActive(otherOrganization)).isFalse();
        Map<String, Object> badJob = claims("terraformApply");
        badJob.put(RunnerToken.CLAIM_JOB_ID, "x");
        assertThat(guard.jobActive(badJob)).isFalse();
        Map<String, Object> badStep = claims("terraformApply");
        badStep.put(RunnerToken.CLAIM_STEP_ID, "x");
        assertThat(guard.stepRunning(badStep)).isFalse();
        Map<String, Object> missingStep = claims("terraformApply");
        missingStep.remove(RunnerToken.CLAIM_STEP_ID);
        assertThat(guard.stepRunning(missingStep)).isFalse();

        Job other = new Job();
        other.setId(8);
        step.setJob(other);
        assertThat(guard.stepRunning(claims("terraformApply"))).isFalse();
        assertThat(guard.isStepRunning("7", step.getId().toString())).isFalse();
        assertThat(guard.isStepRunning("7", "not-a-uuid")).isFalse();
        assertThat(guard.isStepRunning("7", null)).isFalse();

        assertThat(guard.jobActive(Map.of("iss", "TerrakubeInternal"))).isFalse();
        job.setWorkspace(null);
        assertThat(guard.jobActive(claims("terraformApply"))).isFalse();
    }
}
