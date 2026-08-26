package io.terrakube.api.plugin.scheduler.reconciliation;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.util.UUID;

@Service
@Slf4j
@AllArgsConstructor
public class JobReconciliationSweepService {

    private static final String PREFIX_RECONCILIATION_SWEEP = "TerrakubeV2_JobReconciliationSweep";

    private Scheduler scheduler;

    @Transactional
    @PostConstruct
    public void initReconciliationSweep() {
        try {
            log.info("Setup job reconciliation sweep");
            runReconciliationSweep();
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_RECONCILIATION_SWEEP));
            if (jobDetail != null) {
                scheduler.deleteJob(new JobKey(PREFIX_RECONCILIATION_SWEEP));
            }
            setupReconciliationSweep("*/30 * * ? * *");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void runReconciliationSweep() throws SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("JobReconciliationSweep", "JobReconciliationSweepV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(JobReconciliationSweep.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_RECONCILIATION_SWEEP + "_" + UUID.randomUUID())
                .withDescription("JobReconciliationSweepStartup")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_RECONCILIATION_SWEEP + "_" + UUID.randomUUID())
                .withDescription("JobReconciliationSweepStartup")
                .startNow()
                .build();

        log.info("Create schedule for job reconciliation sweep: {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }

    public void setupReconciliationSweep(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("JobReconciliationSweep", "JobReconciliationSweepV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(JobReconciliationSweep.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_RECONCILIATION_SWEEP)
                .withDescription("JobReconciliationSweepV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_RECONCILIATION_SWEEP)
                .withDescription("JobReconciliationSweepV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create schedule job trigger for job reconciliation sweep {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
