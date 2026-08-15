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
public class NotificationOutboxPollerScheduler {

    private static final String PREFIX_NOTIFICATION_OUTBOX_POLLER = "TerrakubeV2_NotificationOutboxPoller";

    private Scheduler scheduler;

    @PostConstruct
    public void initNotificationOutboxPoller() {
        try {
            log.info("Setup notification outbox poller");
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_NOTIFICATION_OUTBOX_POLLER));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_NOTIFICATION_OUTBOX_POLLER));
            }
            setupNotificationOutboxPoller("0 * * ? * *");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void setupNotificationOutboxPoller(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("NotificationOutboxPoller", "NotificationOutboxPollerV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(NotificationOutboxPollerJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_NOTIFICATION_OUTBOX_POLLER)
                .withDescription("NotificationOutboxPollerV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_NOTIFICATION_OUTBOX_POLLER)
                .withDescription("NotificationOutboxPollerV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for notification outbox poller {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
