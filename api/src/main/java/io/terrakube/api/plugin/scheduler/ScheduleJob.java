package io.terrakube.api.plugin.scheduler;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.scheduler.job.tcl.model.ScheduleTemplate;
import io.terrakube.api.plugin.softdelete.SoftDeleteService;
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
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

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

    JobRepository jobRepository;

    StepRepository stepRepository;
    TclService tclService;
    ExecutorService executorService;

    WorkspaceRepository workspaceRepository;

    SoftDeleteService softDeleteService;

    ScheduleJobService scheduleJobService;

    RedisTemplate<String, Object> redisTemplate;

    GitHubWebhookService gitHubWebhookService;
    GlobalVarRepository globalVarRepository;
    VariableRepository variableRepository;


    @Transactional
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        int jobId = jobExecutionContext.getJobDetail().getJobDataMap().getInt(JOB_ID);
        Job job = jobRepository.getReferenceById(jobId);
        boolean deschedule = runExecution(job);
        if (deschedule) {
            redisTemplate.delete(String.valueOf(job.getId()));
            removeJobContext(job, jobExecutionContext);
        }
    }

    // Testing entry point
    protected boolean runExecution(Job job) {
        int jobId = job.getId();
        Date jobExpiration = DateUtils.addHours(job.getCreatedDate(), 6);
        Date currentTime = new Date(System.currentTimeMillis());
        log.info("Job {} should be completed before {}, current time {}", job.getId(), jobExpiration, currentTime);
        if (currentTime.after(jobExpiration)) {
            log.error("Job has been running for more than 6 hours, cancelling running job");
            try {
                job.setStatus(JobStatus.failed);
                jobRepository.save(job);
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

        if (job.getWorkspace().isLocked()) {
            log.warn("Job {}, Workspace is locked. It must be unlocked before Terrakube can execute it.", jobId);
            return false;
        }

        log.info("Checking Job {} Status {}", job.getId(), job.getStatus());
        log.info("Checking previous jobs....");

        boolean canProceed;
        if (job.isBypassQueue()) {
            log.info("Job {} has bypassQueue enabled, checking for active apply/destroy", jobId);
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
                    executePendingJob(job);
                    deschedule = true;
                    break;
                case approved:
                    executeApprovedJobs(job);
                    deschedule = true;
                    break;
                case running:
                    log.info("Job {} running", job.getId());
                    break;
                case completed:
                    deschedule = true;
                    updateJobStepsWithStatus(job.getId(), JobStatus.notExecuted);
                    updateJobStatusOnVcs(job, JobStatus.completed);
                    deleteOldJobs(job);
                    break;
                case cancelled:
                case failed:
                case rejected:
                    log.info("Deleting Failed/Cancelled/Rejected Job Context {} from Quartz", PREFIX_JOB_CONTEXT + job.getId());
                    updateJobStepsWithStatus(job.getId(), JobStatus.failed);
                    updateJobStatusOnVcs(job, JobStatus.failed);
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

        Optional<List<Globalvar>> globalsList = Optional.ofNullable(globalVarRepository.findByOrganization(job.getOrganization()));
        globalsList.ifPresent(variableList -> variableList.forEach(variable -> {
            if (variable.getKey().equals("KEEP_JOB_HISTORY") && variable.getCategory() == Category.ENV) {
                keepHistory.set(Integer.parseInt(variable.getValue()));
            }
        }));

        Optional<List<Variable>> variables = variableRepository.findByWorkspace(job.getWorkspace());
        variables.ifPresent(variableList -> variableList.forEach(variable -> {
            if (variable.getKey().equals("KEEP_JOB_HISTORY") && variable.getCategory() == Category.ENV) {
                keepHistory.set(Integer.parseInt(variable.getValue()));
            }
        }));

        if (keepHistory.get() > 0) {
            log.info("Keeping history of {} jobs", keepHistory);
            Optional<List<Job>> previousJobs = jobRepository.findByWorkspaceAndStatusInAndIdLessThanOrderByIdDesc(
                    job.getWorkspace(),
                    Arrays.asList(JobStatus.failed, JobStatus.completed, JobStatus.rejected, JobStatus.cancelled, JobStatus.noChanges),
                    job.getId()
            );
            if (previousJobs.isPresent()) {
                for (int i = 0; i < previousJobs.get().size(); i++) {
                    if (i >= keepHistory.get()) {
                        Job previousJob = previousJobs.get().get(i);
                        log.info("Deleting Job {} with Status {}", previousJob.getId(), previousJob.getStatus());
                        stepRepository.deleteAll(stepRepository.findByJobId(previousJob.getId()));
                        jobRepository.delete(previousJob);
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

    private void executePendingJob(Job job) {
        job = tclService.initJobConfiguration(job);

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
                    try {
                        executorService.execute(job, stepId, flow.get());
                        job.setStatus(JobStatus.queue);
                        jobRepository.save(job);
                    } catch (ExecutionException e) {
                        errorJobAtStep(job, stepId, e);
                    }
                    break;
                case approval:
                    if (!job.isAutoApply()) {
                        job.setStatus(JobStatus.waitingApproval);
                        job.setApprovalTeam(flow.get().getTeam());
                        jobRepository.save(job);
                        log.info("Waiting Approval for Job {} Step Id {}", job.getId(), stepId);
                    } else {
                        log.info("Auto Approving is enabled for Job {} Step Id {}", job.getId(), stepId);
                        executeApprovedJobs(job);
                    }
                    break;
                case disableWorkspace:
                    log.warn("Disable workspace {} updating status to COMPLETED", job.getId());
                    job.setStatus(JobStatus.completed);
                    jobRepository.save(job);
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
                    } else {
                        job.setStatus(JobStatus.failed);
                        jobRepository.save(job);
                    }
                    break;
                case yamlError:
                    log.error("Terrakube Template error, please verify the template definition");
                    job.setStatus(JobStatus.failed);
                    jobRepository.save(job);
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
        updateJobStatusOnVcs(job, JobStatus.completed);
        updateWorkspaceStatus(job);
        log.info("Update Job {} to completed", job.getId());
    }

    private void errorJobAtStep(Job job, String stepId, Throwable e) {
        String logMessage = String.format(
            "Error when sending context to executor marking job %s as failed, step count %s",
            job.getId(),
            job.getStep().size()
        );
        log.error(logMessage, e);
        job.setStatus(JobStatus.failed);
        jobRepository.save(job);
        Step step = stepRepository.getReferenceById(UUID.fromString(stepId));
        String message = String.format("Error sending to executor: %s", e.getMessage())
                .substring(0, Math.min(e.getMessage().length(), 127));
        step.setName(message);
        stepRepository.save(step);
        updateJobStepsWithStatus(job.getId(), JobStatus.failed);
        updateJobStatusOnVcs(job, JobStatus.unknown);
    }

    private void removeJobContext(Job job, JobExecutionContext jobExecutionContext) {
        try {
            Boolean triggerByStatusChange = jobExecutionContext.getJobDetail().getJobDataMap().getBooleanFromString("isTriggerFromStatusChange");
            if (!triggerByStatusChange.booleanValue()) {
                log.info("Deleting Schedule Job Context {}, InstanceId {}", PREFIX_JOB_CONTEXT + job.getId(), jobExecutionContext.getFireInstanceId());
                jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
            } else {
                String jobIdentity = jobExecutionContext.getJobDetail().getJobDataMap().getString("identity");
                jobExecutionContext.getScheduler().deleteJob(new JobKey(jobIdentity));
            }
        } catch (SchedulerException e) {
            log.error(e.getMessage());
        }
    }

    private void executeApprovedJobs(Job job) {
        job = tclService.initJobConfiguration(job);
        Optional<Flow> flow = Optional.ofNullable(tclService.getNextFlow(job));
        if (flow.isPresent()) {
            log.info("Execute command: {} \n {}", flow.get().getType(), flow.get().getCommands());
            String stepId = tclService.getCurrentStepId(job);
            job.setApprovalTeam("");
            jobRepository.save(job);
            try {
                executorService.execute(job, stepId, flow.get());
                job.setStatus(JobStatus.queue);
                jobRepository.save(job);
            } catch (ExecutionException e) {
                errorJobAtStep(job, stepId, e);
            }
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

    private void updateJobStatusOnVcs(Job job, JobStatus jobStatus) {
        if (job.getVia().equals(JobVia.UI.name()) || job.getVia().equals(JobVia.CLI.name()) || job.getVia().equals(JobVia.Schedule.name())) {
            return;
        }

        switch (job.getWorkspace().getVcs().getVcsType()) {
            case GITHUB:
                gitHubWebhookService.sendCommitStatus(job, jobStatus);
                break;
            case GITLAB:
                gitLabWebhookService.sendCommitStatus(job, jobStatus);
                break;
            default:
                break;
        }
    }

    private boolean isActiveApplyOrDestroyRunning(Workspace workspace, int currentJobId) {
        Optional<List<Job>> runningJobs = jobRepository.findByWorkspaceAndStatusInAndIdLessThan(
                workspace, Arrays.asList(JobStatus.running), currentJobId);

        if (!runningJobs.isPresent() || runningJobs.get().isEmpty()) {
            return false;
        }

        for (Job runningJob : runningJobs.get()) {
            Optional<Step> runningStep = stepRepository.findByJobId(runningJob.getId()).stream()
                    .filter(step -> step.getStatus().equals(JobStatus.running))
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
