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
public class RepoWebhookDeliveryPollerScheduler {

    private static final String PREFIX_REPO_WEBHOOK_DELIVERY_POLLER = "TerrakubeV2_RepoWebhookDeliveryPoller";

    private Scheduler scheduler;

    @PostConstruct
    public void initRepoWebhookDeliveryPoller() {
        try {
            log.info("Setup repo webhook delivery poller");
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_REPO_WEBHOOK_DELIVERY_POLLER));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_REPO_WEBHOOK_DELIVERY_POLLER));
            }
            setupRepoWebhookDeliveryPoller("0 * * ? * *");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void setupRepoWebhookDeliveryPoller(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("RepoWebhookDeliveryPoller", "RepoWebhookDeliveryPollerV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(RepoWebhookDeliveryPollerJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_REPO_WEBHOOK_DELIVERY_POLLER)
                .withDescription("RepoWebhookDeliveryPollerV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_REPO_WEBHOOK_DELIVERY_POLLER)
                .withDescription("RepoWebhookDeliveryPollerV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for repo webhook delivery poller {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
