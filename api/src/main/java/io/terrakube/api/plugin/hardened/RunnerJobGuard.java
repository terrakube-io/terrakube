package io.terrakube.api.plugin.hardened;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "io.terrakube.hardened", name = "enabled", havingValue = "true")
public class RunnerJobGuard {

    public static final Set<JobStatus> FINISHED = EnumSet.of(JobStatus.completed, JobStatus.noChanges,
            JobStatus.notExecuted, JobStatus.rejected, JobStatus.cancelled, JobStatus.failed, JobStatus.NeverExecuted);
    private static final Set<JobStatus> STARTING_OR_RUNNING = EnumSet.of(JobStatus.pending, JobStatus.queue, JobStatus.running);
    private static final Set<JobStatus> STEP_DONE = EnumSet.of(JobStatus.completed, JobStatus.failed);
    public static final Set<String> STATE_WRITING_FLOWS = Set.of("terraformApply", "terraformDestroy", "customScripts");

    private final JobRepository jobRepository;
    private final StepRepository stepRepository;

    public RunnerJobGuard(JobRepository jobRepository, StepRepository stepRepository) {
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
    }

    @Transactional(readOnly = true)
    public boolean jobActive(Map<String, Object> claims) {
        return job(claims).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean stepRunning(Map<String, Object> claims) {
        return step(claims).map(step -> step.getStatus() == JobStatus.running).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean stepStartingOrRunning(Map<String, Object> claims) {
        return step(claims).map(step -> STARTING_OR_RUNNING.contains(step.getStatus())).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean stepReporting(Map<String, Object> claims) {
        Optional<Step> own = step(claims);
        if (own.isEmpty()) {
            return false;
        }
        if (STARTING_OR_RUNNING.contains(own.get().getStatus())) {
            return true;
        }
        return STEP_DONE.contains(own.get().getStatus())
                && stepRepository.findByJobId(own.get().getJob().getId()).stream()
                .noneMatch(other -> !other.getId().equals(own.get().getId()) && other.getStatus() == JobStatus.running);
    }

    @Transactional(readOnly = true)
    public boolean isStepRunning(String jobId, String stepId) {
        try {
            return stepRepository.findById(UUID.fromString(stepId))
                    .filter(step -> step.getJob() != null && String.valueOf(step.getJob().getId()).equals(jobId))
                    .filter(step -> !FINISHED.contains(step.getJob().getStatus()))
                    .map(step -> step.getStatus() == JobStatus.running)
                    .orElse(false);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public boolean canWriteState(Map<String, Object> claims) {
        String flow = RunnerToken.claim(claims, RunnerToken.CLAIM_FLOW);
        return flow != null && STATE_WRITING_FLOWS.contains(flow) && stepRunning(claims);
    }

    private Optional<Job> job(Map<String, Object> claims) {
        if (!RunnerToken.isRunner(claims)) {
            return Optional.empty();
        }
        Optional<Job> job;
        try {
            job = jobRepository.findById(Integer.parseInt(RunnerToken.claim(claims, RunnerToken.CLAIM_JOB_ID)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return job.filter(j -> j.getWorkspace() != null
                && j.getWorkspace().getId().toString().equals(RunnerToken.claim(claims, RunnerToken.CLAIM_WORKSPACE_ID))
                && j.getOrganization() != null
                && j.getOrganization().getId().toString().equals(RunnerToken.claim(claims, RunnerToken.CLAIM_ORGANIZATION_ID))
                && !FINISHED.contains(j.getStatus()));
    }

    private Optional<Step> step(Map<String, Object> claims) {
        Optional<Job> job = job(claims);
        if (job.isEmpty()) {
            return Optional.empty();
        }
        UUID stepId;
        try {
            stepId = UUID.fromString(RunnerToken.claim(claims, RunnerToken.CLAIM_STEP_ID));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        return stepRepository.findById(stepId)
                .filter(step -> step.getJob() != null && step.getJob().getId() == job.get().getId());
    }
}
