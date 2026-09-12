package io.terrakube.api.plugin.scheduler;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
@Service
public class ScheduleJobService {

    public static final String PREFIX_JOB = "TerrakubeV2_Trigger_";
    public static final String PREFIX_JOB_CONTEXT = "TerrakubeV2_Job_";
    public static final int JOB_CONTEXT_INTERVAL = 30;

    Scheduler scheduler;

    StepRepository stepRepository;

    WorkspaceRepository workspaceRepository;

    public void createJobTrigger(String cronExpression, String triggerId) throws ParseException, SchedulerException {

        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(ScheduleJobTrigger.TRIGGER_ID, triggerId);

        JobDetail jobDetail = JobBuilder.newJob().ofType(ScheduleJobTrigger.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_JOB + triggerId)
                .withDescription(triggerId)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_JOB + triggerId)
                .withDescription(triggerId)
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(cronExpression)))
                .build();

        log.info("Create Schedule Job Trigger {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }

    public void createJobContext(Job job) throws ParseException, SchedulerException {

        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(ScheduleJob.JOB_ID, job.getId());

        JobDetail jobDetail = JobBuilder.newJob().ofType(ScheduleJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_JOB_CONTEXT + job.getId())
                .withDescription(String.valueOf(job.getId()))
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(PREFIX_JOB_CONTEXT + job.getId())
                .withDescription(String.valueOf(job.getId()))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(JOB_CONTEXT_INTERVAL).repeatForever())
                .startAt(Date.from(Instant.now().plusSeconds(JOB_CONTEXT_INTERVAL)))
                .build();

        log.info("Create Job Context {}", jobDetail.getKey());

        Workspace workspace = job.getWorkspace();
        if (workspace != null) {
            workspaceRepository.save(workspace);
        }
        scheduler.scheduleJob(jobDetail, trigger);
        scheduler.triggerJob(jobDetail.getKey());
    }

    public void createJobContextNow(Job job) throws SchedulerException {

        String random = UUID.randomUUID().toString();
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(ScheduleJob.JOB_ID, job.getId());
        jobDataMap.put("isTriggerFromStatusChange", "true");
        jobDataMap.put("identity", PREFIX_JOB_CONTEXT + job.getId() + "_" + random);

        JobDetail jobDetail = JobBuilder.newJob().ofType(ScheduleJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_JOB_CONTEXT + job.getId() + "_" + random)
                .withDescription(String.valueOf(job.getId()))
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(PREFIX_JOB_CONTEXT + job.getId() + "_" + random)
                .withDescription(String.valueOf(job.getId()))
                .startNow()
                .build();

        log.info("Running Job Context Now: {}, Identity: {}", job.getId(),
                PREFIX_JOB_CONTEXT + job.getId() + "_" + random);
        scheduler.scheduleJob(jobDetail, trigger);
    }

    public void deleteJobTrigger(String triggerId) throws ParseException, SchedulerException {
        log.info("Delete Schedule Job Trigger {}", triggerId);
        scheduler.deleteJob(new JobKey(PREFIX_JOB + triggerId));
    }

    @Transactional
    public void deleteJobContext(int jobId) throws ParseException, SchedulerException {
        log.info("Delete Job Context {}", jobId);
        scheduler.deleteJob(new JobKey(PREFIX_JOB_CONTEXT + jobId));
        for (Step step : stepRepository.findByJobId(jobId)) {
            if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                step.setStatus(JobStatus.cancelled);
                stepRepository.save(step);
            }
        }
    }

}