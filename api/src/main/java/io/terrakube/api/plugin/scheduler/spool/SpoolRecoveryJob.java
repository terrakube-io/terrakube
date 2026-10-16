package io.terrakube.api.plugin.scheduler.spool;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SpoolRecoveryJob implements Job {

    private final SpoolRecoveryService spoolRecoveryService;

    @Autowired
    public SpoolRecoveryJob(SpoolRecoveryService spoolRecoveryService) {
        this.spoolRecoveryService = spoolRecoveryService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            int recovered = spoolRecoveryService.recoverPendingSpooledSteps();
            if (recovered > 0) {
                log.info("SpoolRecoveryJob recovered {} steps", recovered);
            }
        } catch (Exception e) {
            log.error("Error executing SpoolRecoveryJob: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
