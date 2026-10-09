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
public class RunTriggerEventPollerScheduler {

    private static final String PREFIX_RUN_TRIGGER_EVENT_POLLER = "TerrakubeV2_RunTriggerEventPoller";

    private Scheduler scheduler;

    @PostConstruct
    public void initRunTriggerEventPoller() {
        try {
            log.info("Setup run trigger event poller");
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_RUN_TRIGGER_EVENT_POLLER));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_RUN_TRIGGER_EVENT_POLLER));
            }
            // Every 10 seconds - faster than notification's once-a-minute cadence, since a
            // cascade's first downstream run should be visibly prompt.
            setupRunTriggerEventPoller("*/10 * * ? * *");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void setupRunTriggerEventPoller(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("RunTriggerEventPoller", "RunTriggerEventPollerV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(RunTriggerEventPollerJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_RUN_TRIGGER_EVENT_POLLER)
                .withDescription("RunTriggerEventPollerV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_RUN_TRIGGER_EVENT_POLLER)
                .withDescription("RunTriggerEventPollerV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for run trigger event poller {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
