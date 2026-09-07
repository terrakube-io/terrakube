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
public class PolicyDriftEvaluationScheduler {

    private static final String PREFIX_POLICY_DRIFT_EVALUATION = "TerrakubeV2_PolicyDriftEvaluation";

    private final Scheduler scheduler;
    private final String cronSchedule;

    public PolicyDriftEvaluationScheduler(
            Scheduler scheduler,
            @Value("${io.terrakube.policy.drift.cron:0 0 2 * * ?}") String cronSchedule) {
        this.scheduler = scheduler;
        this.cronSchedule = cronSchedule;
    }

    @PostConstruct
    public void initPolicyDriftEvaluation() {
        try {
            log.info("Setup policy drift evaluation schedule: {}", cronSchedule);
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_POLICY_DRIFT_EVALUATION));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_POLICY_DRIFT_EVALUATION));
            }
            setupPolicyDriftEvaluation(cronSchedule);
        } catch (Exception ex) {
            log.error("Failed to initialize policy drift evaluation schedule: {}", ex.getMessage(), ex);
        }
    }

    public void setupPolicyDriftEvaluation(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("PolicyDriftEvaluation", "PolicyDriftEvaluationV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(PolicyDriftEvaluationJob.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_POLICY_DRIFT_EVALUATION)
                .withDescription("PolicyDriftEvaluationV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_POLICY_DRIFT_EVALUATION)
                .withDescription("PolicyDriftEvaluationV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Created schedule job trigger for policy drift evaluation {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
