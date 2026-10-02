package io.terrakube.api.plugin.scheduler.trigger;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;

import java.text.ParseException;

@Service
@Slf4j
@AllArgsConstructor
public class RunTriggerEventRetentionScheduler {

    private static final String PREFIX_RUN_TRIGGER_EVENT_RETENTION = "TerrakubeV2_RunTriggerEventRetention";

    private Scheduler scheduler;

    @PostConstruct
    public void initRunTriggerEventRetention() {
        try {
            log.info("Setup run trigger event retention sweep");
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_RUN_TRIGGER_EVENT_RETENTION));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_RUN_TRIGGER_EVENT_RETENTION));
            }
            // Once a day at 03:05, a few minutes after notification's own 03:00 retention sweep.
            setupRunTriggerEventRetention("0 5 3 * * ?");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void setupRunTriggerEventRetention(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("RunTriggerEventRetention", "RunTriggerEventRetentionV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(RunTriggerEventRetentionJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_RUN_TRIGGER_EVENT_RETENTION)
                .withDescription("RunTriggerEventRetentionV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_RUN_TRIGGER_EVENT_RETENTION)
                .withDescription("RunTriggerEventRetentionV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for run trigger event retention {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
