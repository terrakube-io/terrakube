package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * A dedicated Spring @Service bean so mutating database operations invoked from
 * {@link PolicyDriftEvaluationJob} (a Quartz Job) go through this bean's Spring transactional proxy.
 *
 * Quartz's SchedulerFactoryBean instantiates Job instances directly rather than looking them
 * up from the ApplicationContext, so a Job class never gets an AOP proxy of its own.
 * Any @Transactional declared directly on a Quartz Job would silently never apply.
 */
@Slf4j
@Service
@AllArgsConstructor
public class PolicyDriftDispatchTransactions {

    private static final String POLICY_EVAL_TCL =
            "flow:\n" +
            "  - type: policyEvaluation\n" +
            "    step: 100\n" +
            "    name: OPA Policy Drift Evaluation\n";

    private final JobRepository jobRepository;
    private final ScheduleJobService scheduleJobService;

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
        return dispatchPolicyEvaluationJob(workspace, terraformPlan, "serviceAccount", JobVia.DRIFT.getValue());
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
            job.setVia(via != null ? via : JobVia.DRIFT.getValue());
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
