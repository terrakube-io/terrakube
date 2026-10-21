package io.terrakube.api.plugin.scheduler.spool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.ParseException;

@Service
@Slf4j
public class SpoolRecoveryScheduler {

    private static final String PREFIX_SPOOL_RECOVERY = "TerrakubeV2_SpoolRecoverySweep";

    private final Scheduler scheduler;
    private final SpoolRecoveryProperties properties;

    @Autowired
    public SpoolRecoveryScheduler(@Autowired(required = false) Scheduler scheduler, SpoolRecoveryProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @PostConstruct
    public void initSpoolRecovery() {
        if (!properties.isEnabled()) {
            log.info("Spool recovery sweeper is disabled by configuration");
            return;
        }
        if (scheduler == null) {
            log.warn("Scheduler is not available, skipping spool recovery scheduling");
            return;
        }
        try {
            log.info("Setting up spool recovery sweeper with cron: {}", properties.getCron());
            JobKey jobKey = new JobKey(PREFIX_SPOOL_RECOVERY);
            if (scheduler.checkExists(jobKey)) {
                log.info("Spool recovery sweeper job already scheduled: {}", jobKey);
                return;
            }
            setupSpoolRecoverySchedule(properties.getCron());
        } catch (Exception e) {
            log.error("Failed to initialize spool recovery scheduler: {}", e.getMessage(), e);
        }
    }

    public void setupSpoolRecoverySchedule(String cronExpression) throws ParseException, SchedulerException {
        JobKey jobKey = new JobKey(PREFIX_SPOOL_RECOVERY);
        if (scheduler.checkExists(jobKey)) {
            log.info("Spool recovery sweep job already exists in scheduler: {}", jobKey);
            return;
        }

        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("SpoolRecoverySweep", "SpoolRecoverySweepV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(SpoolRecoveryJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_SPOOL_RECOVERY)
                .withDescription("SpoolRecoverySweepV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_SPOOL_RECOVERY)
                .withDescription("SpoolRecoverySweepV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(cronExpression)))
                .build();

        try {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Created schedule trigger for spool recovery sweep: {}", jobDetail.getKey());
        } catch (org.quartz.ObjectAlreadyExistsException e) {
            log.info("Spool recovery sweep job was already scheduled by another replica: {}", jobDetail.getKey());
        }
    }
}
