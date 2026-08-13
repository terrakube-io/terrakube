package io.terrakube.api.plugin.scheduler.notification;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;

import java.text.ParseException;

@Service
@Slf4j
@AllArgsConstructor
public class NotificationOutboxRetentionScheduler {

    private static final String PREFIX_NOTIFICATION_OUTBOX_RETENTION = "TerrakubeV2_NotificationOutboxRetention";

    private Scheduler scheduler;

    @PostConstruct
    public void initNotificationOutboxRetention() {
        try {
            log.info("Setup notification outbox retention sweep");
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_NOTIFICATION_OUTBOX_RETENTION));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_NOTIFICATION_OUTBOX_RETENTION));
            }
            // Once a day at 03:00 - a DELETE sweep has no reason to run on the poller's
            // once-a-minute cadence.
            setupNotificationOutboxRetention("0 0 3 * * ?");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void setupNotificationOutboxRetention(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("NotificationOutboxRetention", "NotificationOutboxRetentionV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(NotificationOutboxRetentionJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_NOTIFICATION_OUTBOX_RETENTION)
                .withDescription("NotificationOutboxRetentionV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_NOTIFICATION_OUTBOX_RETENTION)
                .withDescription("NotificationOutboxRetentionV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for notification outbox retention {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
