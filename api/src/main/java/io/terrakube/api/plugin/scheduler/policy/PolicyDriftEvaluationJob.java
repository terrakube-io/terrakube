package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.plugin.policy.PolicyResolutionService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@DisallowConcurrentExecution
public class PolicyDriftEvaluationJob implements org.quartz.Job {

    private static final String POLICY_EVAL_TCL =
            "flow:\n" +
            "  - type: policyEvaluation\n" +
            "    step: 100\n" +
            "    name: OPA Policy Drift Evaluation\n";

    private final WorkspaceRepository workspaceRepository;
    private final JobRepository jobRepository;
    private final ScheduleJobService scheduleJobService;
    private final PolicyResolutionService policyResolutionService;

    public PolicyDriftEvaluationJob(
            WorkspaceRepository workspaceRepository,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            PolicyResolutionService policyResolutionService) {
        this.workspaceRepository = workspaceRepository;
        this.jobRepository = jobRepository;
        this.scheduleJobService = scheduleJobService;
        this.policyResolutionService = policyResolutionService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Starting PolicyDriftEvaluationJob scheduled drift evaluation scan...");
        try {
            List<Workspace> workspaces = workspaceRepository.findAll();
            int dispatchedJobs = 0;

            for (Workspace workspace : workspaces) {
                if (workspace.isDeleted() || workspace.isLocked() || JobStatus.NeverExecuted.equals(workspace.getLastJobStatus())) {
                    continue;
                }

                Optional<Job> lastCompletedJob = jobRepository.findFirstByWorkspaceAndAndStatusInOrderByIdDesc(
                        workspace, List.of(JobStatus.completed)
                );
                if (lastCompletedJob.isEmpty() || lastCompletedJob.get().getTerraformPlan() == null || lastCompletedJob.get().getTerraformPlan().isBlank()) {
                    log.info("Skipping drift evaluation for workspace {} as it has no completed run with a terraform plan", workspace.getName());
                    continue;
                }

                // Check if any policy applies to this workspace
                Job mockJob = new Job();
                mockJob.setWorkspace(workspace);
                mockJob.setOrganization(workspace.getOrganization());
                List<PolicyContext> policies = policyResolutionService.resolvePoliciesForJob(mockJob);

                if (policies.isEmpty()) {
                    continue;
                }

                dispatchDriftEvaluationJob(workspace, lastCompletedJob.get().getTerraformPlan());
                dispatchedJobs++;
            }

            log.info("PolicyDriftEvaluationJob completed. Dispatched {} drift evaluation jobs.", dispatchedJobs);
        } catch (Exception e) {
            log.error("Error during PolicyDriftEvaluationJob execution: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }

    @Transactional
    public Job dispatchDriftEvaluationJob(Workspace workspace) {
        Optional<Job> lastCompletedJob = jobRepository.findFirstByWorkspaceAndAndStatusInOrderByIdDesc(
                workspace, List.of(JobStatus.completed)
        );
        String planUrl = lastCompletedJob.map(Job::getTerraformPlan).orElse(null);
        return dispatchDriftEvaluationJob(workspace, planUrl);
    }

    @Transactional
    public Job dispatchDriftEvaluationJob(Workspace workspace, String terraformPlan) {
        return dispatchPolicyEvaluationJob(workspace, terraformPlan, "serviceAccount", JobVia.SCHEDULE.getValue());
    }

    @Transactional
    public Job dispatchPolicyEvaluationJob(Workspace workspace, String terraformPlan, String username, String via) {
        try {
            Job job = new Job();
            String encodedTcl = Base64.getEncoder().encodeToString(POLICY_EVAL_TCL.getBytes(StandardCharsets.UTF_8));
            job.setTcl(encodedTcl);
            job.setWorkspace(workspace);
            job.setOrganization(workspace.getOrganization());
            job.setStatus(JobStatus.pending);
            job.setPlanChanges(true);
            job.setTerraformPlan(terraformPlan);
            job.setCreatedBy(username != null ? username : "serviceAccount");
            job.setUpdatedBy(username != null ? username : "serviceAccount");
            job.setVia(via != null ? via : JobVia.SCHEDULE.getValue());
            Date now = new Date();
            job.setCreatedDate(now);
            job.setUpdatedDate(now);

            job = jobRepository.save(job);
            log.info("Dispatched policyEvaluation job {} for Workspace {} via {}", job.getId(), workspace.getName(), job.getVia());

            scheduleJobService.createJobContext(job);
            return job;
        } catch (Exception e) {
            log.error("Failed to dispatch policy evaluation job for Workspace {}: {}", workspace.getName(), e.getMessage());
            throw new RuntimeException("Failed to dispatch policy evaluation job: " + e.getMessage(), e);
        }
    }
}
