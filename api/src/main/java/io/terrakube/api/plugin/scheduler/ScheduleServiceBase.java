package io.terrakube.api.plugin.scheduler;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public abstract class ScheduleServiceBase {
    @Autowired
    Scheduler scheduler;

    public void createTask(String cronExpression, String id) throws ParseException, SchedulerException {
        if (jobExists(id)) {
            deleteTask(id);
            createQuartzJob(cronExpression, id);
        } else {
            createQuartzJob(cronExpression, id);
        }
    }

    // Fire a repeated job that accepts a frequency in seconds
    public void createTask(int frequencyInSeconds, String id) throws SchedulerException {
        createTask(frequencyInSeconds, id, false);
    }
    
    public void createTask(int frequencyInSeconds, String id, boolean startNow) throws SchedulerException {
        if (jobExists(id)) {
            deleteTask(id);
            createQuartzJob(frequencyInSeconds, id, startNow);
        } else {
            createQuartzJob(frequencyInSeconds, id, startNow);
        }
    }

    private void createQuartzJob(String cronExpression, String id) throws SchedulerException, ParseException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(getJobDataKey(), id);

        scheduler.getJobGroupNames();

        @SuppressWarnings("unchecked")
        JobDetail jobDetail = JobBuilder.newJob().ofType(getJobClass())
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(getJobPrefix() + id)
                .withDescription(id)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(getJobPrefix() + id)
                .withDescription(id)
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(cronExpression)))
                .build();

        log.info("Create {} Trigger {}", getJobType(), jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }

    // Fire a repeated job that accepts a frequency in seconds
    private void createQuartzJob(int frequencyInSeconds, String id, boolean startNow) throws SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(getJobDataKey(), id);

        scheduler.getJobGroupNames();

        @SuppressWarnings("unchecked")
        JobDetail jobDetail = JobBuilder.newJob().ofType(getJobClass())
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(getJobPrefix() + id)
                .withDescription(id)
                .build();
        TriggerBuilder<Trigger> triggerBuilder = TriggerBuilder.newTrigger();
        if(startNow) {
            triggerBuilder.startNow();
        } else {
            triggerBuilder.startAt(Date.from(Instant.now().plusSeconds(frequencyInSeconds)));
        }

        Trigger trigger = triggerBuilder.forJob(jobDetail)
                .withIdentity(getJobPrefix() + id)
                .withDescription(id)
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(frequencyInSeconds)
                        .withMisfireHandlingInstructionFireNow()
                        .withRepeatCount(frequencyInSeconds))
                .build();

        log.info("Create {} Trigger {}", getJobType(), jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }

    public void deleteTask(String id) throws SchedulerException {
        log.info("Delete {} Trigger {}", getJobType(), id);
        scheduler.deleteJob(new JobKey(getJobPrefix() + id));
    }

    protected abstract String getJobPrefix();

    protected abstract String getJobType();

    @SuppressWarnings("rawtypes")
    protected abstract Class getJobClass();

    public abstract String getJobDataKey();

    private boolean jobExists(String id) {
        boolean jobExists = false;
        try {
            if (scheduler.getJobDetail(new JobKey(getJobPrefix() + id)) != null) {
                jobExists = true;
                log.info("JobId {} on {} exists", id, getJobType());
            }
        } catch (SchedulerException e) {
            log.error(e.getMessage());
        }
        return jobExists;
    }
}
