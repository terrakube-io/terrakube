package io.terrakube.api.plugin.scheduler.module;

import java.text.ParseException;

import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class ModuleRefreshPollerScheduler {

    public static final String PREFIX_MODULE_REFRESH_POLLER = "TerrakubeV2_ModuleRefreshPoller";

    private Scheduler scheduler;

    // Every replica recreates the job at startup, so a rolling deploy also repairs a lost or old trigger.
    @PostConstruct
    public void initModuleRefreshPoller() {
        try {
            log.info("Setup module refresh poller");
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_MODULE_REFRESH_POLLER));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_MODULE_REFRESH_POLLER));
            }
            setupModuleRefreshPoller("0 * * ? * *");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    // During a rolling deploy an old replica can acquire the trigger, fail to load ModuleRefreshPollerJob
    // and leave the trigger in ERROR, where Quartz never fires it again. This runs on every replica (it is
    // not a Quartz job), so whichever new replica checks first puts the trigger back to normal.
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void resetPollerTriggerFromError() {
        TriggerKey key = new TriggerKey(PREFIX_MODULE_REFRESH_POLLER);
        try {
            if (scheduler.getTriggerState(key) == Trigger.TriggerState.ERROR) {
                log.warn("Module refresh poller trigger is in ERROR state, resetting it");
                scheduler.resetTriggerFromErrorState(key);
            }
        } catch (SchedulerException e) {
            log.error("Failed to check the module refresh poller trigger: {}", e.getMessage());
        }
    }

    public void setupModuleRefreshPoller(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("ModuleRefreshPoller", "ModuleRefreshPollerV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(ModuleRefreshPollerJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_MODULE_REFRESH_POLLER)
                .withDescription("ModuleRefreshPollerV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_MODULE_REFRESH_POLLER)
                .withDescription("ModuleRefreshPollerV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for module refresh poller {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
