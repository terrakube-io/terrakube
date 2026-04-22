package io.terrakube.api.plugin.scheduler.inactive;

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
public class InactiveJobsService {

    private static final String PREFIX_INACTIVE_JOBS = "TerrakubeV2_InactiveJobs";

    private Scheduler scheduler;

    @Transactional
    @PostConstruct
    public void initInactiveJobs() {
        try {
            log.info("Setup job to cancelled inactive jobs");
            runInactiveJobs();
            JobDetail jobDetail = scheduler.getJobDetail(new JobKey(PREFIX_INACTIVE_JOBS));
            log.info("jobDetail is null {}", jobDetail == null);
            if(jobDetail == null){
                setupInactiveJob("0 */5 * ? * *");
            } else {
                log.info("Delete Old Quartz Job for inactive jobs");
                scheduler.deleteJob(new JobKey(PREFIX_INACTIVE_JOBS));
                setupInactiveJob("0 */5 * ? * *");
            }
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }

    }

    public void runInactiveJobs() throws SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("InactiveJobs", "InactiveJobsV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(InactiveJobs.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_INACTIVE_JOBS + "_" + UUID.randomUUID())
                .withDescription("InactiveJobsStartup")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_INACTIVE_JOBS + "_" + UUID.randomUUID())
                .withDescription("InactiveJobsStartup")
                .startNow()
                .build();

        log.info("Create Schedule for inactive jobs: {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }

    public void setupInactiveJob(String quartzSchedule) throws ParseException, SchedulerException {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("InactiveJobs", "InactiveJobsV1");

        JobDetail jobDetail = JobBuilder.newJob().ofType(InactiveJobs.class)
                .storeDurably()
                .setJobData(jobDataMap)
                .withIdentity(PREFIX_INACTIVE_JOBS)
                .withDescription("InactiveJobsV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(PREFIX_INACTIVE_JOBS)
                .withDescription("InactiveJobsV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        log.info("Create Schedule Job Trigger for inactive jobs {}", jobDetail.getKey());
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
