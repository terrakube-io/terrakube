package io.terrakube.api.plugin.scheduler.webhook;

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
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class RepoWebhookDeliveryRetentionScheduler {

    private static final String PREFIX_REPO_WEBHOOK_DELIVERY_RETENTION = "TerrakubeV2_RepoWebhookDeliveryRetention";

    private Scheduler scheduler;

    @PostConstruct
    public void initRepoWebhookDeliveryRetention() {
        try {
            log.info("Setup repo webhook delivery retention sweep");
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_REPO_WEBHOOK_DELIVERY_RETENTION));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_REPO_WEBHOOK_DELIVERY_RETENTION));
            }
            // Once a day at 03:30 - offset from the notification outbox retention sweep's 03:00
            // so the two DELETE sweeps don't contend for the same tables at the same instant.
            setupRepoWebhookDeliveryRetention("0 30 3 * * ?");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void setupRepoWebhookDeliveryRetention(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("RepoWebhookDeliveryRetention", "RepoWebhookDeliveryRetentionV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(RepoWebhookDeliveryRetentionJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_REPO_WEBHOOK_DELIVERY_RETENTION)
                .withDescription("RepoWebhookDeliveryRetentionV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_REPO_WEBHOOK_DELIVERY_RETENTION)
                .withDescription("RepoWebhookDeliveryRetentionV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for repo webhook delivery retention {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
