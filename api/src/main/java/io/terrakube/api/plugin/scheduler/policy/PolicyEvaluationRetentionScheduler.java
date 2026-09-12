package io.terrakube.api.plugin.scheduler.policy;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.ParseException;

@Service
@Slf4j
public class PolicyEvaluationRetentionScheduler {

    private static final String PREFIX_POLICY_EVALUATION_RETENTION = "TerrakubeV2_PolicyEvaluationRetention";

    private final Scheduler scheduler;
    private final String cronSchedule;

    public PolicyEvaluationRetentionScheduler(
            Scheduler scheduler,
            @Value("${io.terrakube.policy.evaluation.retentionCron:0 0 4 * * ?}") String cronSchedule) {
        this.scheduler = scheduler;
        this.cronSchedule = cronSchedule;
    }

    @PostConstruct
    public void initPolicyEvaluationRetention() {
        try {
            log.info("Setup policy evaluation retention sweep schedule: {}", cronSchedule);
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_POLICY_EVALUATION_RETENTION));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_POLICY_EVALUATION_RETENTION));
            }
            setupPolicyEvaluationRetention(cronSchedule);
        } catch (Exception ex) {
            log.error("Failed to initialize policy evaluation retention sweep: {}", ex.getMessage(), ex);
        }
    }

    public void setupPolicyEvaluationRetention(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("PolicyEvaluationRetention", "PolicyEvaluationRetentionV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(PolicyEvaluationRetentionJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_POLICY_EVALUATION_RETENTION)
                .withDescription("PolicyEvaluationRetentionV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_POLICY_EVALUATION_RETENTION)
                .withDescription("PolicyEvaluationRetentionV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)).withMisfireHandlingInstructionDoNothing())
                .build();

        log.info("Created schedule job trigger for policy evaluation retention {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
