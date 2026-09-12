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
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@DisallowConcurrentExecution
public class PolicyDriftEvaluationJob implements org.quartz.Job {

    private static final String FOLLOW_UP_TRIGGER_PREFIX = "TerrakubeV2_PolicyDriftEvaluation_FollowUp";

    private final WorkspaceRepository workspaceRepository;
    private final JobRepository jobRepository;
    private final ScheduleJobService scheduleJobService;
    private final PolicyResolutionService policyResolutionService;
    private final PolicyDriftDispatchTransactions policyDriftDispatchTransactions;
    private final int batchSize;
    private final int maxJobsPerRun;
    private final int maxActiveJobs;
    private final int followUpDelayMinutes;
    private final int cooldownHours;

    public PolicyDriftEvaluationJob(
            WorkspaceRepository workspaceRepository,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            PolicyResolutionService policyResolutionService) {
        this(workspaceRepository, jobRepository, scheduleJobService, policyResolutionService,
                new PolicyDriftDispatchTransactions(jobRepository, scheduleJobService), 50, 100, 5, 2, 12);
    }

    public PolicyDriftEvaluationJob(
            WorkspaceRepository workspaceRepository,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            PolicyResolutionService policyResolutionService,
            PolicyDriftDispatchTransactions policyDriftDispatchTransactions) {
        this(workspaceRepository, jobRepository, scheduleJobService, policyResolutionService,
                policyDriftDispatchTransactions, 50, 100, 5, 2, 12);
    }

    public PolicyDriftEvaluationJob(
            WorkspaceRepository workspaceRepository,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            PolicyResolutionService policyResolutionService,
            int batchSize,
            int maxJobsPerRun) {
        this(workspaceRepository, jobRepository, scheduleJobService, policyResolutionService,
                new PolicyDriftDispatchTransactions(jobRepository, scheduleJobService), batchSize, maxJobsPerRun, 5, 2, 12);
    }

    public PolicyDriftEvaluationJob(
            WorkspaceRepository workspaceRepository,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            PolicyResolutionService policyResolutionService,
            int batchSize,
            int maxJobsPerRun,
            int maxActiveJobs,
            int followUpDelayMinutes,
            int cooldownHours) {
        this(workspaceRepository, jobRepository, scheduleJobService, policyResolutionService,
                new PolicyDriftDispatchTransactions(jobRepository, scheduleJobService),
                batchSize, maxJobsPerRun, maxActiveJobs, followUpDelayMinutes, cooldownHours);
    }

    @Autowired
    public PolicyDriftEvaluationJob(
            WorkspaceRepository workspaceRepository,
            JobRepository jobRepository,
            ScheduleJobService scheduleJobService,
            PolicyResolutionService policyResolutionService,
            PolicyDriftDispatchTransactions policyDriftDispatchTransactions,
            @Value("${io.terrakube.policy.drift.batchSize:50}") int batchSize,
            @Value("${io.terrakube.policy.drift.maxJobsPerRun:100}") int maxJobsPerRun,
            @Value("${io.terrakube.policy.drift.maxActiveJobs:5}") int maxActiveJobs,
            @Value("${io.terrakube.policy.drift.followUpDelayMinutes:2}") int followUpDelayMinutes,
            @Value("${io.terrakube.policy.drift.cooldownHours:12}") int cooldownHours) {
        this.workspaceRepository = workspaceRepository;
        this.jobRepository = jobRepository;
        this.scheduleJobService = scheduleJobService;
        this.policyResolutionService = policyResolutionService;
        this.policyDriftDispatchTransactions = policyDriftDispatchTransactions;
        this.batchSize = batchSize;
        this.maxJobsPerRun = maxJobsPerRun;
        this.maxActiveJobs = maxActiveJobs;
        this.followUpDelayMinutes = followUpDelayMinutes;
        this.cooldownHours = cooldownHours;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Starting PolicyDriftEvaluationJob scheduled drift evaluation scan (batchSize={}, maxJobsPerRun={}, maxActiveJobs={}, cooldownHours={})...",
                batchSize, maxJobsPerRun, maxActiveJobs, cooldownHours);
        try {
            long activeDriftJobs = jobRepository.countByViaAndStatusIn(
                    JobVia.DRIFT.getValue(),
                    List.of(JobStatus.pending, JobStatus.queue, JobStatus.running)
            );

            if (maxActiveJobs > 0 && activeDriftJobs >= maxActiveJobs) {
                log.info("Active drift jobs count ({}) already meets or exceeds ceiling ({}); scheduling follow-up in {} min.",
                        activeDriftJobs, maxActiveJobs, followUpDelayMinutes);
                scheduleFollowUpTrigger(context);
                return;
            }

            int pageNumber = 0;
            int dispatchedJobs = 0;
            boolean hitLimit = false;
            Page<Workspace> workspacePage;

            do {
                workspacePage = workspaceRepository.findAll(PageRequest.of(pageNumber, batchSize));
                for (Workspace workspace : workspacePage.getContent()) {
                    if (maxJobsPerRun > 0 && dispatchedJobs >= maxJobsPerRun) {
                        log.info("Reached maximum drift jobs limit ({}) for this run. Halting scan and scheduling follow-up.", maxJobsPerRun);
                        scheduleFollowUpTrigger(context);
                        hitLimit = true;
                        break;
                    }

                    if (maxActiveJobs > 0 && activeDriftJobs >= maxActiveJobs) {
                        log.info("Active drift jobs reached concurrency ceiling ({}). Halting scan and scheduling follow-up in {} min.",
                                maxActiveJobs, followUpDelayMinutes);
                        scheduleFollowUpTrigger(context);
                        hitLimit = true;
                        break;
                    }

                    if (workspace.isDeleted() || workspace.isLocked() || JobStatus.NeverExecuted.equals(workspace.getLastJobStatus())) {
                        continue;
                    }

                    // Skip workspace if it currently has active/running jobs
                    List<Integer> activeWorkspaceJobs = jobRepository.findActiveJobIdsByWorkspace(workspace.getId().toString());
                    if (activeWorkspaceJobs != null && !activeWorkspaceJobs.isEmpty()) {
                        log.debug("Skipping drift evaluation for workspace {} as it already has active jobs: {}",
                                workspace.getName(), activeWorkspaceJobs);
                        continue;
                    }

                    // Skip workspace if it underwent a drift evaluation recently within the cooldown window
                    Optional<Job> latestJob = jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace);
                    if (latestJob.isPresent() && JobVia.DRIFT.getValue().equals(latestJob.get().getVia())) {
                        Date createdDate = latestJob.get().getCreatedDate();
                        if (createdDate != null && isWithinCooldown(createdDate, cooldownHours)) {
                            log.debug("Skipping drift evaluation for workspace {} due to recent drift run at {}",
                                    workspace.getName(), createdDate);
                            continue;
                        }
                    }

                    Optional<Job> lastCompletedJob = jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(
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

                    policyDriftDispatchTransactions.dispatchDriftEvaluationJob(workspace, lastCompletedJob.get().getTerraformPlan());
                    dispatchedJobs++;
                    activeDriftJobs++;
                }

                if (hitLimit) {
                    break;
                }
                pageNumber++;
            } while (workspacePage.hasNext());

            log.info("PolicyDriftEvaluationJob completed. Dispatched {} drift evaluation jobs across {} scanned pages.",
                    dispatchedJobs, pageNumber);
        } catch (Exception e) {
            log.error("Error during PolicyDriftEvaluationJob execution: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }

    private boolean isWithinCooldown(Date createdDate, int hours) {
        if (hours <= 0) {
            return false;
        }
        long elapsedMillis = System.currentTimeMillis() - createdDate.getTime();
        return elapsedMillis < (long) hours * 3600_000L;
    }

    private void scheduleFollowUpTrigger(JobExecutionContext context) {
        if (context == null || context.getScheduler() == null || context.getJobDetail() == null || followUpDelayMinutes <= 0) {
            return;
        }
        try {
            Scheduler scheduler = context.getScheduler();
            TriggerKey followUpKey = new TriggerKey(FOLLOW_UP_TRIGGER_PREFIX);
            if (!scheduler.checkExists(followUpKey)) {
                Trigger followUpTrigger = TriggerBuilder.newTrigger()
                        .withIdentity(followUpKey)
                        .forJob(context.getJobDetail().getKey())
                        .startAt(Date.from(Instant.now().plus(followUpDelayMinutes, ChronoUnit.MINUTES)))
                        .build();
                scheduler.scheduleJob(followUpTrigger);
                log.info("Scheduled PolicyDriftEvaluationJob follow-up trigger {} to run in {} minutes",
                        followUpKey, followUpDelayMinutes);
            }
        } catch (Exception e) {
            log.warn("Could not schedule follow-up trigger for PolicyDriftEvaluationJob: {}", e.getMessage());
        }
    }

    public Job dispatchDriftEvaluationJob(Workspace workspace) {
        return policyDriftDispatchTransactions.dispatchDriftEvaluationJob(workspace);
    }

    public Job dispatchDriftEvaluationJob(Workspace workspace, String terraformPlan) {
        return policyDriftDispatchTransactions.dispatchDriftEvaluationJob(workspace, terraformPlan);
    }

    public Job dispatchPolicyEvaluationJob(Workspace workspace, String terraformPlan, String username, String via) {
        return policyDriftDispatchTransactions.dispatchPolicyEvaluationJob(workspace, terraformPlan, username, via);
    }
}
