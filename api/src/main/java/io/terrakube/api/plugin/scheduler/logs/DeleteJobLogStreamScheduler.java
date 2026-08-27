package io.terrakube.api.plugin.scheduler.logs;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.ParseException;

@Service
@Slf4j
public class DeleteJobLogStreamScheduler {

    private static final String KEY = "TerrakubeV2_DeleteJobLogStream";

    private final Scheduler scheduler;
    private final String cron;

    public DeleteJobLogStreamScheduler(
            Scheduler scheduler,
            @Value("${io.terrakube.logs.stream-cleanup.cron:0 */10 * * * ?}") String cron) {
        this.scheduler = scheduler;
        this.cron = cron;
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Setup job log Redis stream cleanup sweep ({})", cron);
            if (scheduler.getJobDetail(new JobKey(KEY)) != null) {
                scheduler.deleteJob(new JobKey(KEY));
            }
            schedule(cron);
        } catch (Exception ex) {
            log.error("Could not schedule job log stream cleanup: {}", ex.getMessage());
        }
    }

    private void schedule(String quartzSchedule) throws ParseException, SchedulerException {
        JobDetail jobDetail = JobBuilder.newJob().ofType(DeleteJobLogStreamJob.class)
                .storeDurably()
                .withIdentity(KEY)
                .withDescription("DeleteJobLogStreamV1")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .forJob(jobDetail)
                .withIdentity(KEY)
                .withDescription("DeleteJobLogStreamV1")
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(quartzSchedule)))
                .build();

        scheduler.scheduleJob(jobDetail, trigger);
    }
}
