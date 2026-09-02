package io.terrakube.api.plugin.scheduler;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorUnavailableException;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.scheduler.job.tcl.model.ScheduleTemplate;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.plugin.variable.IncompleteVariableException;
import io.terrakube.api.plugin.variable.InvalidVariableCategoryException;
import io.terrakube.api.plugin.variable.WorkspaceVariableValidationService;
import io.terrakube.api.plugin.vcs.PrCommentService;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.hibernate.LazyInitializationException;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

// @DisallowConcurrentExecution only stops Quartz from running the SAME JobDetail key concurrently
// with itself. It gives no protection across DIFFERENT JobDetails for the same job id - and
// ScheduleJobService.createJobContextNow mints a brand-new, uniquely-suffixed JobDetail on every
// call (used by JobManageHook on every job update, plus wakeNextDispatchableJob and
// ExecutorAvailabilityListener below), so a job's long-lived 30s recurring trigger can easily
// overlap one or more of these one-shot triggers. The EXECUTION_LOCK_PREFIX Redis lock in
// runExecution is what actually guarantees only one worker processes a given job id at a time.
@DisallowConcurrentExecution
@AllArgsConstructor
@Component
@Getter
@Setter
@Slf4j
public class ScheduleJob implements org.quartz.Job {
    private final ScheduleRepository scheduleRepository;
    private final TemplateRepository templateRepository;

    public static final String JOB_ID = "jobId";
    private final GitLabWebhookService gitLabWebhookService;

    // Guarantees only one worker (in this pod or any other replica) processes a given job id at a
    // time - held via withExecutionLock for the entire duration of doRunExecution, not just around
    // a single write. A second overlapping firing that reaches this after the first has already
    // dispatched and moved on would otherwise redo status checks, VCS notifications, PR comments
    // and history cleanup against stale state, and - since a step is only marked queue/running
    // after the fact - could re-dispatch a terraform apply against an already-changed state.
    // doRunExecution runs with no single transaction wrapping it (see execute()) - every write it
    // makes is its own already-committed Spring Data JPA call by the time it returns, so by the
    // time this lock releases, everything doRunExecution wrote is already durably visible to
    // whichever worker acquires the lock next. TTL comfortably covers the slowest thing
    // execute() does (PersistentExecutorService's connect+response timeout, 10s + 60s), so an
    // orphaned lock (e.g. a pod crash mid-run) self-heals well before the next 30s retry needs it.
    private static final String EXECUTION_LOCK_PREFIX = "job-execution-lock:";
    private static final Duration EXECUTION_LOCK_TTL = Duration.ofSeconds(90);

    JobRepository jobRepository;

    StepRepository stepRepository;
    TclService tclService;
    ExecutorService executorService;

    WorkspaceRepository workspaceRepository;

    SoftDeleteService softDeleteService;

    ScheduleJobService scheduleJobService;

    RedisTemplate<String, Object> redisTemplate;

    GitHubWebhookService gitHubWebhookService;
    AzDevOpsWebhookService azDevOpsWebhookService;
    PrCommentService prCommentService;
    GlobalVarRepository globalVarRepository;
    VariableRepository variableRepository;
    WorkspaceVariableValidationService workspaceVariableValidationService;
    // Real job status transitions happen here via plain jobRepository.save(), never through an
    // Elide JSON:API/GraphQL request - JobNotificationHook (an Elide LifeCycleHook) never sees
    // them. Every save below that follows a job.setStatus(...) call is followed by a call to
    // this so status-change notifications actually fire for real runs, not just for a job
    // updated via a direct API PATCH.
    JobNotificationTrigger jobNotificationTrigger;

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        int jobId = jobExecutionContext.getJobDetail().getJobDataMap().getInt(JOB_ID);
        withExecutionLock(jobId, () -> {
            // findById() (not getReferenceById()) issues a real SELECT immediately, so job -
            // along with workspace/organization/vcs, all plain @ManyToOne with no fetch override
            // and therefore JPA-default EAGER - is fully materialized here, in the one place this
            // method still needs an open Hibernate session. Everything doRunExecution reads off
            // job afterwards is safe even once this repository call's own transaction has closed.
            // An empty result is not an error here - see below.
            Optional<Job> existingJob = jobRepository.findById(jobId);
            if (existingJob.isEmpty()) {
                // The Job row no longer exists - deleted (or soft-deleted, which the entity's
                // @SQLRestriction makes equally invisible) after this trigger was scheduled,
                // typically by KEEP_JOB_HISTORY pruning racing this job's own terminal-status
                // cleanup tick under load. The trigger can never succeed and would otherwise
                // refire forever, so remove it by id (the entity is gone; only the id is usable).
                log.warn("Job {} no longer exists (deleted after scheduling, e.g. KEEP_JOB_HISTORY pruning); removing orphaned job context", jobId);
                removeJobContext(jobId, jobExecutionContext);
                return true;
            }
            Job job = existingJob.get();

            boolean shouldDeschedule;
            try {
                // No transaction wraps this call - see the EXECUTION_LOCK_PREFIX comment above and
                // this class's top-of-file comment for why that's safe for the execution lock's
                // invariant. Every jobRepository/stepRepository/workspaceRepository.save() call
                // doRunExecution (or something it calls) makes now runs, and commits, on its own.
                shouldDeschedule = doRunExecution(job);
            } catch (LazyInitializationException e) {
                // Safety net: doRunExecution's own extensive test suite (ScheduleJobTest) mocks
                // every repository, so it cannot catch a real Hibernate lazy-loading regression -
                // if some association gets touched here that isn't one of the eagerly-fetched ones
                // findById() already materialized (or the job.getStep() collection this class's
                // errorJobAtStep fixed the one known touch-point for), fail this attempt safely
                // instead of letting a confusing unhandled exception surface. The existing 30s
                // recurring trigger retries it.
                log.error("Job {} hit a lazy-loading error outside its expected transaction "
                        + "boundary, will retry: {}", jobId, e.getMessage(), e);
                return false;
            }

            if (shouldDeschedule) {
                redisTemplate.delete(String.valueOf(job.getId()));
                removeJobContext(job.getId(), jobExecutionContext);
            }
            return shouldDeschedule;
        });
    }

    // Testing entry point: exercises doRunExecution's business logic directly against mocks, so
    // it holds the same execution lock as execute() but without a real Spring transaction - there
    // is no persistence context in these tests for one to commit.
    protected boolean runExecution(Job job) {
        return withExecutionLock(job.getId(), () -> doRunExecution(job));
    }

    private boolean withExecutionLock(int jobId, BooleanSupplier work) {
        if (!acquireExecutionLock(jobId)) {
            log.info("Job {} is already being processed by another worker, skipping this run", jobId);
            return false;
        }
        try {
            return work.getAsBoolean();
        } finally {
            releaseExecutionLock(jobId);
        }
    }

    private boolean doRunExecution(Job job) {
        int jobId = job.getId();
        Date jobExpiration = DateUtils.addHours(job.getCreatedDate(), 6);
        Date currentTime = new Date(System.currentTimeMillis());
        log.info("Job {} should be completed before {}, current time {}", job.getId(), jobExpiration, currentTime);
        if (currentTime.after(jobExpiration)) {
            log.error("Job has been running for more than 6 hours, cancelling running job");
            try {
                job.setStatus(JobStatus.failed);
                jobRepository.save(job);
                jobNotificationTrigger.notifyStatusChanged(job);
                log.warn("Deleting Job Context {} from Quartz", PREFIX_JOB_CONTEXT + job.getId());
                updateJobStepsWithStatus(job.getId(), JobStatus.failed);
                updateJobStatusOnVcs(job, JobStatus.unknown);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            log.warn("Closing Job");
            return true;
        }

        if (job.getWorkspace() == null) {
            log.warn("Workspace does not exist anymore, deleting job context for {}", jobId);
            return true;
        }

        // The apply job created for a "terrakube apply" PR comment locks the workspace itself
        // (see WebhookService.handlePrCommentCommand) to keep other jobs out while it runs. Without
        // isOwnPrApplyLock() exempting that same job, this guard would block it from ever progressing,
        // so the workspace would stay locked forever since only postPrCommentIfNeeded() unlocks it.
        if (job.getWorkspace().isLocked() && !isOwnPrApplyLock(job)) {
            log.warn("Job {}, Workspace is locked. It must be unlocked before Terrakube can execute it.", jobId);
            return false;
        }

        log.info("Checking Job {} Status {}", job.getId(), job.getStatus());
        log.info("Checking previous jobs....");

        boolean canProceed;
        if (tclService.isTemplatePlanOnly(job.getTemplateReference()) && !tclService.isCliTemplate(job.getTemplateReference())) {
            log.info("Job {} is plan-only (bypassQueue), checking for active apply/destroy", jobId);
            canProceed = !isActiveApplyOrDestroyRunning(job.getWorkspace(), job.getId());
            if (!canProceed) {
                log.info("Job {} waiting for active apply/destroy to complete", jobId);
            }
        } else {
            Optional<List<Job>> previousJobs = jobRepository.findByWorkspaceAndStatusNotInAndIdLessThan(
                    job.getWorkspace(),
                    Arrays.asList(JobStatus.failed, JobStatus.completed, JobStatus.rejected, JobStatus.cancelled, JobStatus.noChanges),
                    job.getId()
            );
            canProceed = !previousJobs.isPresent() || previousJobs.get().isEmpty();
            if (!canProceed) {
                log.warn("Job {} is waiting for previous jobs to be completed...", jobId);
            }
        }

        boolean deschedule = false;
        if (canProceed) {

            switch (job.getStatus()) {
                case pending:
                    if (!job.isPlanChanges()) {
                         throw new AssertionError(String.format("Expected pending job %d to have plan changes", jobId));
                    }
                    log.info("Executing pending job {}", jobId);
                    deschedule = executePendingJob(job);
                    break;
                case approved:
                    deschedule = executeApprovedJobs(job);
                    break;
                case running:
                    log.info("Job {} running", job.getId());
                    break;
                case completed:
                    deschedule = true;
                    updateJobStepsWithStatus(job.getId(), JobStatus.notExecuted);
                    updateJobStatusOnVcs(job, JobStatus.completed);
                    postPrCommentIfNeeded(job);
                    deleteOldJobs(job);
                    break;
                case cancelled:
                case failed:
                case rejected:
                    if (job.getStatus().equals(JobStatus.rejected)) {
                        executeOnRejectCommands(job);
                    }
                    log.info("Deleting Failed/Cancelled/Rejected Job Context {} from Quartz", PREFIX_JOB_CONTEXT + job.getId());
                    updateJobStepsWithStatus(job.getId(), JobStatus.failed);
                    updateJobStatusOnVcs(job, JobStatus.failed);
                    postPrCommentIfNeeded(job);
                    deschedule = true;
                    deleteOldJobs(job);
                    break;
                default:
                    log.info("Job {} Status {}", job.getId(), job.getStatus());
                    break;
            }
            updateWorkspaceStatus(job);
        }
        return deschedule;
    }

    private void deleteOldJobs(Job job) {
        AtomicInteger keepHistory = new AtomicInteger();
        keepHistory.set(0);
        AtomicBoolean softDelete = new AtomicBoolean(false);

        Optional<List<Globalvar>> globalsList = Optional.ofNullable(globalVarRepository.findByOrganization(job.getOrganization()));
        globalsList.ifPresent(variableList -> variableList.forEach(variable -> {
            if (variable.getKey().equals("KEEP_JOB_HISTORY") && variable.getCategory() == Category.ENV) {
                keepHistory.set(Integer.parseInt(variable.getValue()));
            }
            if (variable.getKey().equals("KEEP_JOB_HISTORY_SOFT_DELETE") && variable.getCategory() == Category.ENV) {
                softDelete.set(Boolean.parseBoolean(variable.getValue()));
            }
        }));

        Optional<List<Variable>> variables = variableRepository.findByWorkspace(job.getWorkspace());
        variables.ifPresent(variableList -> variableList.forEach(variable -> {
            if (variable.getKey().equals("KEEP_JOB_HISTORY") && variable.getCategory() == Category.ENV) {
                keepHistory.set(Integer.parseInt(variable.getValue()));
            }
            if (variable.getKey().equals("KEEP_JOB_HISTORY_SOFT_DELETE") && variable.getCategory() == Category.ENV) {
                softDelete.set(Boolean.parseBoolean(variable.getValue()));
            }
        }));

        if (keepHistory.get() > 0) {
            log.info("Keeping history of {} jobs (softDelete={})", keepHistory, softDelete.get());
            Optional<List<Job>> previousJobs = jobRepository.findByWorkspaceAndStatusInAndIdLessThanOrderByIdDesc(
                    job.getWorkspace(),
                    Arrays.asList(JobStatus.failed, JobStatus.completed, JobStatus.rejected, JobStatus.cancelled, JobStatus.noChanges),
                    job.getId()
            );
            if (previousJobs.isPresent()) {
                for (int i = 0; i < previousJobs.get().size(); i++) {
                    if (i >= keepHistory.get()) {
                        Job previousJob = previousJobs.get().get(i);
                        // Remove the job's Quartz context BEFORE the row disappears (hard delete)
                        // or becomes invisible to Hibernate (soft delete + @SQLRestriction): a
                        // surviving trigger whose row is gone throws EntityNotFoundException on
                        // every fire and can never clean itself up. Under load this race is
                        // common - the pruned job's own terminal-status tick (which normally
                        // removes the context) may not have run yet. Already-removed contexts
                        // make this a harmless no-op; a scheduler hiccup must not abort pruning.
                        try {
                            scheduleJobService.deleteJobContext(previousJob.getId());
                        } catch (Exception e) {
                            log.warn("Could not remove job context for pruned job {}: {}", previousJob.getId(), e.getMessage());
                        }
                        if (softDelete.get()) {
                            log.info("Soft deleting Job {} with Status {}", previousJob.getId(), previousJob.getStatus());
                            previousJob.setDeleted(true);
                            jobRepository.save(previousJob);
                        } else {
                            log.info("Deleting Job {} with Status {}", previousJob.getId(), previousJob.getStatus());
                            stepRepository.deleteAll(stepRepository.findByJobId(previousJob.getId()));
                            jobRepository.delete(previousJob);
                        }
                    }
                }
            }
        } else {
            log.info("Keeping history for {}", job.getWorkspace().getName());
        }
    }

    private void updateWorkspaceStatus(Job job) {
        log.info("Updating last status for workspace {} to {}", job.getWorkspace().getName(), job.getStatus());
        job.getWorkspace().setLastJobStatus(job.getStatus());
        job.getWorkspace().setLastJobDate(new Date(System.currentTimeMillis()));
        workspaceRepository.save(job.getWorkspace());
    }

    // Returns whether the job's Quartz trigger should be descheduled. False keeps it alive so
    // the existing 30s retrigger (JOB_CONTEXT_INTERVAL) retries once an executor is free, instead
    // of failing the job just because the whole pool was busy at this particular attempt.
    private boolean executePendingJob(Job job) {
        job = tclService.initJobConfiguration(job);
        if (failJobIfWorkspaceVariablesAreIncomplete(job)) {
            return true;
        }

        Optional<Flow> flow = Optional.ofNullable(tclService.getNextFlow(job));
        if (flow.isPresent()) {
            log.info("Execute command: {} \n {}", flow.get().getType(), flow.get().getCommands());
            String stepId = tclService.getCurrentStepId(job);
            FlowType tempFlowType = FlowType.valueOf(flow.get().getType());
            switch (tempFlowType) {
                case terraformPlanDestroy:
                case terraformPlan:
                case terraformApply:
                case terraformDestroy:
                case customScripts:
                    if (!isNextInDispatchOrder(job, stepId)) {
                        return false;
                    }
                    try {
                        executorService.execute(job, stepId, flow.get());
                        job.setStatus(JobStatus.queue);
                        jobRepository.save(job);
                        jobNotificationTrigger.notifyStatusChanged(job);
                        wakeNextDispatchableJob();
                    } catch (ExecutorUnavailableException e) {
                        log.warn("No executor available for Job {} Step {}, will retry: {}", job.getId(), stepId, e.getMessage());
                        return false;
                    } catch (ExecutionException e) {
                        errorJobAtStep(job, stepId, e);
                    }
                    break;
                case approval:
                    if (!job.isAutoApply()) {
                        job.setStatus(JobStatus.waitingApproval);
                        job.setApprovalTeam(flow.get().getTeam());
                        jobRepository.save(job);
                        jobNotificationTrigger.notifyStatusChanged(job);
                        log.info("Waiting Approval for Job {} Step Id {}", job.getId(), stepId);
                    } else {
                        log.info("Auto Approving is enabled for Job {} Step Id {}", job.getId(), stepId);
                        return executeApprovedJobs(job);
                    }
                    break;
                case disableWorkspace:
                    log.warn("Disable workspace {} updating status to COMPLETED", job.getId());
                    job.setStatus(JobStatus.completed);
                    jobRepository.save(job);
                    jobNotificationTrigger.notifyStatusChanged(job);
                    log.warn("Disable workspace scheduler for {} {}", job.getWorkspace().getId(), job.getWorkspace().getName());
                    softDeleteService.disableWorkspaceSchedules(job.getWorkspace());
                    log.warn("Update workspace deleted to true");
                    Workspace workspace = job.getWorkspace();
                    workspace.setDeleted(true);
                    workspace.setName("DELETED_" + UUID.randomUUID());
                    workspaceRepository.save(workspace);
                    break;
                case scheduleTemplates:
                    log.info("Creating new schedules for this workspace");
                    if (setupScheduler(job, flow.get())) {
                        log.info("Schedule completed successfully");

                        Step step = stepRepository.getReferenceById(UUID.fromString(stepId));
                        step.setStatus(JobStatus.completed);
                        log.info("Updating Step {} to completed", stepId);
                        stepRepository.save(step);

                        log.info("Updating Job {} to pending to continue execution", stepId);
                        job.setStatus(JobStatus.pending);
                        jobRepository.save(job);
                        jobNotificationTrigger.notifyStatusChanged(job);
                    } else {
                        job.setStatus(JobStatus.failed);
                        jobRepository.save(job);
                        jobNotificationTrigger.notifyStatusChanged(job);
                    }
                    break;
                case yamlError:
                    log.error("Terrakube Template error, please verify the template definition");
                    job.setStatus(JobStatus.failed);
                    jobRepository.save(job);
                    jobNotificationTrigger.notifyStatusChanged(job);
                    updateJobStepsWithStatus(job.getId(), JobStatus.failed);
                    updateJobStatusOnVcs(job, JobStatus.unknown);
                    break;
                default:
                    log.error("FlowType not supported");
                    break;
            }
        } else {
            completeJob(job);
            deleteOldJobs(job);
        }
        return true;
    }

    private boolean setupScheduler(Job job, Flow flow) {
        boolean success = true;
        for (ScheduleTemplate scheduleTemplate : flow.getTemplates()) {
            Template template = templateRepository.getByOrganizationNameAndName(job.getOrganization().getName(), scheduleTemplate.getName());

            if (template != null) {
                Schedule schedule = new Schedule();
                schedule.setWorkspace(job.getWorkspace());
                schedule.setCron(scheduleTemplate.getSchedule());
                schedule.setEnabled(true);
                schedule.setCreatedBy(job.getCreatedBy());
                schedule.setCreatedDate(job.getCreatedDate());
                schedule.setTemplateReference(template.getId().toString());
                schedule.setDescription("Schedule from Job " + job.getId());

                schedule = scheduleRepository.save(schedule);

                try {
                    scheduleJobService.createJobTrigger(schedule.getCron(), schedule.getId().toString());
                } catch (ParseException | SchedulerException e) {
                    log.error(e.getMessage());
                    success = false;
                }
            } else {
                log.error("Unable to find template with name {} in organization {}", scheduleTemplate.getName(), job.getOrganization().getName());
                success = false;
                break;
            }

        }

        return success;
    }

    private void completeJob(Job job) {
        job.setStatus(JobStatus.completed);
        jobRepository.save(job);
        jobNotificationTrigger.notifyStatusChanged(job);
        updateJobStatusOnVcs(job, JobStatus.completed);
        postPrCommentIfNeeded(job);
        updateWorkspaceStatus(job);
        log.info("Update Job {} to completed", job.getId());
    }

    // Fails closed: if Redis itself is unreachable, treat it the same as losing the lock race
    // rather than running unprotected. A duplicate/overlapping run is worse than a job waiting
    // out a Redis blip for its next 30s retry.
    private boolean acquireExecutionLock(int jobId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(EXECUTION_LOCK_PREFIX + jobId, "1", EXECUTION_LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException e) {
            log.warn("Could not reach Redis to acquire execution lock for Job {}, will retry: {}", jobId, e.getMessage());
            return false;
        }
    }

    private void releaseExecutionLock(int jobId) {
        try {
            redisTemplate.delete(EXECUTION_LOCK_PREFIX + jobId);
        } catch (DataAccessException e) {
            // The lock's TTL (EXECUTION_LOCK_TTL) self-heals this; nothing else to do here.
            log.warn("Could not reach Redis to release execution lock for Job {}: {}", jobId, e.getMessage());
        }
    }

    // Fails closed: if we can't determine dispatch order, treat it the same as losing the race
    // rather than risking a newer job jumping ahead of an older one.
    private boolean isNextInDispatchOrder(Job job, String stepId) {
        try {
            if (!jobRepository.isJobNextInDispatchOrder(job.getId())) {
                log.info("Job {} Step {} is not yet the oldest job waiting for the executor pool, will retry", job.getId(), stepId);
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            log.warn("Could not determine dispatch order for Job {} Step {}, will retry: {}", job.getId(), stepId, e.getMessage());
            return false;
        }
    }

    // Best-effort: wakes the next-in-line job immediately. If this fails, its own 30s recurring
    // trigger still covers it.
    private void wakeNextDispatchableJob() {
        try {
            Integer nextJobId = jobRepository.findNextDispatchableJobId();
            if (nextJobId != null) {
                scheduleJobService.createJobContextNow(jobRepository.getReferenceById(nextJobId));
            }
        } catch (DataAccessException | SchedulerException e) {
            log.warn("Could not wake the next dispatchable job: {}", e.getMessage());
        }
    }

    private void errorJobAtStep(Job job, String stepId, Throwable e) {
        String logMessage = String.format(
            "Error when sending context to executor marking job %s as failed, step count %s",
            job.getId(),
            stepRepository.findByJobId(job.getId()).size()
        );
        log.error(logMessage, e);
        job.setStatus(JobStatus.failed);
        jobRepository.save(job);
        jobNotificationTrigger.notifyStatusChanged(job);
        Step step = stepRepository.getReferenceById(UUID.fromString(stepId));
        String message = String.format("Error sending to executor: %s", e.getMessage())
                .substring(0, Math.min(e.getMessage().length(), 127));
        step.setName(message);
        stepRepository.save(step);
        updateJobStepsWithStatus(job.getId(), JobStatus.failed);
        updateJobStatusOnVcs(job, JobStatus.unknown);
    }

    // Takes the raw job id, not the entity: the orphaned-trigger path (see execute) calls this
    // precisely when the Job row no longer exists, so a Job parameter would itself throw.
    private void removeJobContext(int jobId, JobExecutionContext jobExecutionContext) {
        try {
            Boolean triggerByStatusChange = jobExecutionContext.getJobDetail().getJobDataMap().getBooleanFromString("isTriggerFromStatusChange");
            if (!triggerByStatusChange.booleanValue()) {
                log.info("Deleting Schedule Job Context {}, InstanceId {}", PREFIX_JOB_CONTEXT + jobId, jobExecutionContext.getFireInstanceId());
                jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + jobId));
            } else {
                String jobIdentity = jobExecutionContext.getJobDetail().getJobDataMap().getString("identity");
                jobExecutionContext.getScheduler().deleteJob(new JobKey(jobIdentity));
            }
        } catch (SchedulerException e) {
            log.error(e.getMessage());
        }
    }

    // Returns whether the job's Quartz trigger should be descheduled; see executePendingJob.
    private boolean executeApprovedJobs(Job job) {
        job = tclService.initJobConfiguration(job);
        if (failJobIfWorkspaceVariablesAreIncomplete(job)) {
            return true;
        }
        Optional<Flow> flow = Optional.ofNullable(tclService.getNextFlow(job));
        if (flow.isPresent()) {
            log.info("Execute command: {} \n {}", flow.get().getType(), flow.get().getCommands());
            String stepId = tclService.getCurrentStepId(job);
            job.setApprovalTeam("");
            jobRepository.save(job);
            if (!isNextInDispatchOrder(job, stepId)) {
                return false;
            }
            try {
                executorService.execute(job, stepId, flow.get());
                job.setStatus(JobStatus.queue);
                jobRepository.save(job);
                jobNotificationTrigger.notifyStatusChanged(job);
                wakeNextDispatchableJob();
            } catch (ExecutorUnavailableException e) {
                log.warn("No executor available for Job {} Step {}, will retry: {}", job.getId(), stepId, e.getMessage());
                return false;
            } catch (ExecutionException e) {
                errorJobAtStep(job, stepId, e);
            }
        }
        return true;
    }

    // Fire-and-forget: the job must stay rejected, so unlike the pending/approved paths the
    // job is never moved to queue and dispatch errors only get logged. The executor skips its
    // job status callbacks for rejected jobs so this run cannot resurrect the flow.
    private void executeOnRejectCommands(Job job) {
        try {
            Flow flow = tclService.getNextFlow(job);
            if (flow != null
                    && FlowType.approval.name().equals(flow.getType())
                    && flow.getOnReject() != null
                    && !flow.getOnReject().isEmpty()) {
                // Re-fire safety: concurrent firings are serialized by the execution lock in
                // runExecution, and this dispatch happens before the teardown marks the approval
                // step failed in the same transaction - so a later firing finds no pending step,
                // getNextFlow returns null, and the hook cannot be dispatched twice.
                String stepId = tclService.getCurrentStepId(job);
                flow.setCommands(flow.getOnReject());
                log.info("Executing onReject commands for job {} step {}", job.getId(), stepId);
                executorService.execute(job, stepId, flow);
            }
        } catch (Exception e) {
            log.error("Failed to execute onReject commands for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private void updateJobStepsWithStatus(int jobId, JobStatus jobStatus) {
        log.warn("Cancelling pending steps");
        for (Step step : stepRepository.findByJobId(jobId)) {
            if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                step.setStatus(jobStatus);
                stepRepository.save(step);
            }
        }
    }

    private boolean failJobIfWorkspaceVariablesAreIncomplete(Job job) {
        try {
            workspaceVariableValidationService.validateWorkspaceVariables(job.getWorkspace());
            return false;
        } catch (InvalidVariableCategoryException exception) {
            String failureMessage = workspaceVariableValidationService.buildInvalidCategoryMessage(job.getWorkspace());
            log.warn("Failing job {} because of variables with no category", job.getId(), exception);
            failJobWithVariableValidationError(job, failureMessage, WorkspaceVariableValidationService.INVALID_CATEGORY_STEP_NAME);
            return true;
        } catch (IncompleteVariableException exception) {
            String failureMessage = workspaceVariableValidationService.buildIncompleteVariableMessage(job.getWorkspace());
            log.warn("Failing job {} because of incomplete variables", job.getId(), exception);
            failJobWithVariableValidationError(job, failureMessage, WorkspaceVariableValidationService.INCOMPLETE_VARIABLE_STEP_NAME);
            return true;
        }
    }

    private void failJobWithVariableValidationError(Job job, String failureMessage, String stepName) {
        job.setStatus(JobStatus.failed);
        job.setOutput(failureMessage);
        jobRepository.save(job);
        jobNotificationTrigger.notifyStatusChanged(job);

        try {
            String stepId = tclService.getCurrentStepId(job);
            Step step = stepRepository.getReferenceById(UUID.fromString(stepId));
            step.setName(stepName);
            stepRepository.save(step);
        } catch (Exception stepException) {
            log.warn("Unable to update step for job {}", job.getId(), stepException);
        }

        updateJobStepsWithStatus(job.getId(), JobStatus.failed);
        updateJobStatusOnVcs(job, JobStatus.failed);
    }

    /**
     * True when the workspace's current lock is the one this exact job's PR-apply-comment flow
     * created for itself (see WebhookService.handlePrCommentCommand), rather than an unrelated
     * manual or concurrent lock that should still block the job.
     */
    private boolean isOwnPrApplyLock(Job job) {
        return job.isAutoApply() && job.getPrNumber() != null
                && WebhookService.buildPrApplyLockDescription(job.getPrNumber()).equals(job.getWorkspace().getLockDescription());
    }

    private void postPrCommentIfNeeded(Job job) {
        if (job.getPrNumber() == null || job.getPrNumber() == 0) return;

        try {
            prCommentService.acknowledgeCompletion(job);
            // job.isAutoApply() marks the job created by the "terrakube apply" PR comment
            // specifically (see WebhookService.handlePrCommentCommand); tclService.isTemplatePlanOnly()
            // reflects the *template's* nature and can misclassify this job if the workspace's
            // default template isn't recognized as a full apply template.
            if (job.isAutoApply()) {
                prCommentService.postApplyResult(job);
                Workspace workspace = job.getWorkspace();
                workspace.setLocked(false);
                workspace.setLockDescription(null);
                workspaceRepository.save(workspace);
                log.info("Unlocked workspace {} after PR #{} apply completed", workspace.getName(), job.getPrNumber());
            } else {
                prCommentService.postPlanResult(job);
            }
        } catch (Exception e) {
            log.error("Error posting PR comment for job {}: {}", job.getId(), e.getMessage());
        }
    }

    void updateJobStatusOnVcs(Job job, JobStatus jobStatus) {
        if (job.getVia().equals(JobVia.UI.getValue()) || job.getVia().equals(JobVia.CLI.getValue()) || job.getVia().equals(JobVia.SCHEDULE.getValue())) {
            return;
        }

        // Notifying the VCS is a side effect of the job, not part of it: a VCS-side failure
        // (expired token, provider outage, rate limit, missing commit) must never abort the
        // job itself, since callers here include the job-completion path.
        try {
            String runSummary = prCommentService.extractRunSummary(job).orElse(null);
            switch (job.getWorkspace().getVcs().getVcsType()) {
                case GITHUB:
                    gitHubWebhookService.sendCommitStatus(job, jobStatus, runSummary);
                    break;
                case GITLAB:
                    gitLabWebhookService.sendCommitStatus(job, jobStatus, runSummary);
                    break;
                case AZURE_DEVOPS:
                case AZURE_SP_MI:
                    azDevOpsWebhookService.sendCommitStatus(job, jobStatus, runSummary);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to update VCS commit status for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private boolean isActiveApplyOrDestroyRunning(Workspace workspace, int currentJobId) {
        Optional<List<Job>> runningJobs = jobRepository.findByWorkspaceAndStatusInAndIdLessThan(
                workspace, Arrays.asList(JobStatus.running, JobStatus.queue), currentJobId);

        if (!runningJobs.isPresent() || runningJobs.get().isEmpty()) {
            return false;
        }

        for (Job runningJob : runningJobs.get()) {
            Optional<Step> runningStep = stepRepository.findByJobId(runningJob.getId()).stream()
                    .filter(step -> step.getStatus().equals(JobStatus.running) || step.getStatus().equals(JobStatus.queue))
                    .findFirst();

            if (runningStep.isPresent()) {
                String flowType = tclService.getFlowTypeForStep(runningJob, runningStep.get().getStepNumber());
                if (flowType != null && (
                        flowType.equals(FlowType.terraformApply.toString()) ||
                        flowType.equals(FlowType.terraformDestroy.toString()) ||
                        flowType.equals(FlowType.customScripts.toString())
                )) {
                    log.info("Job {} has active apply/destroy/customScripts step running in job {}",
                            currentJobId, runningJob.getId());
                    return true;
                }
            }
        }
        return false;
    }
}
