package io.terrakube.api.plugin.hardened;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.yahoo.elide.core.security.ChangeSpec;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "io.terrakube.hardened", name = "enabled", havingValue = "true")
public class RunnerChangePolicy {

    private static final Set<JobStatus> JOB_STATUS_WRITES = EnumSet.of(JobStatus.running, JobStatus.pending,
            JobStatus.completed, JobStatus.failed, JobStatus.waitingApproval, JobStatus.noChanges);
    private static final Set<JobStatus> STEP_STATUS_WRITES = EnumSet.of(JobStatus.running, JobStatus.completed, JobStatus.failed);

    private final RunnerJobGuard guard;
    private final String hostname;

    public RunnerChangePolicy(RunnerJobGuard guard, @Value("${io.terrakube.hostname}") String hostname) {
        this.guard = guard;
        this.hostname = hostname;
    }

    public boolean canRead(Map<String, Object> claims) {
        return guard.jobActive(claims);
    }

    public boolean canChangeJob(Map<String, Object> claims, Job job, Optional<ChangeSpec> change) {
        if (!guard.stepReporting(claims)) {
            return false;
        }
        if (change.isEmpty()) {
            return true;
        }
        Object original = change.get().getOriginal();
        Object modified = change.get().getModified();
        String organizationId = RunnerToken.claim(claims, RunnerToken.CLAIM_ORGANIZATION_ID);
        String workspaceId = RunnerToken.claim(claims, RunnerToken.CLAIM_WORKSPACE_ID);
        String jobId = RunnerToken.claim(claims, RunnerToken.CLAIM_JOB_ID);
        return switch (change.get().getFieldName()) {
            case "status" -> modified instanceof JobStatus status && JOB_STATUS_WRITES.contains(status)
                    && original != JobStatus.waitingApproval;
            case "commitId" -> original == null || Objects.equals(original, modified);
            case "planChanges" -> true;
            case "output" -> Objects.equals(modified, (original == null ? "" : original) + " Step "
                    + RunnerToken.claim(claims, RunnerToken.CLAIM_STEP_ID) + " completed\n");
            case "terraformPlan" -> modified == null || modified.toString().isEmpty() || modified.toString().startsWith(
                    String.format("https://%s/tfstate/v1/organization/%s/workspace/%s/jobId/%s/step/", hostname, organizationId, workspaceId, jobId));
            default -> false;
        };
    }

    public boolean canChangeStep(Map<String, Object> claims, Step step, Optional<ChangeSpec> change) {
        if (!guard.stepStartingOrRunning(claims)) {
            return false;
        }
        if (change.isEmpty()) {
            return true;
        }
        Object modified = change.get().getModified();
        return switch (change.get().getFieldName()) {
            case "status" -> modified instanceof JobStatus status && STEP_STATUS_WRITES.contains(status);
            case "output" -> modified == null || modified.toString().equals(String.format(
                    "https://%s/tfoutput/v1/organization/%s/job/%s/step/%s", hostname,
                    RunnerToken.claim(claims, RunnerToken.CLAIM_ORGANIZATION_ID),
                    RunnerToken.claim(claims, RunnerToken.CLAIM_JOB_ID), step.getId()));
            default -> false;
        };
    }
}
