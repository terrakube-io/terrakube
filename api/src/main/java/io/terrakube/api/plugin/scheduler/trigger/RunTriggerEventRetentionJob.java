package io.terrakube.api.plugin.scheduler.trigger;

import java.util.Date;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

// Daily housekeeping, not the hot poll path. DB work delegated to RunTriggerEventTransactions,
// same reasoning as RunTriggerEventPollerJob: a Quartz Job never gets its own AOP proxy.
@Slf4j
@Component
@DisallowConcurrentExecution
public class RunTriggerEventRetentionJob implements Job {

    private final RunTriggerEventTransactions runTriggerEventTransactions;
    private final RunTriggerProperties properties;

    public RunTriggerEventRetentionJob(RunTriggerEventTransactions runTriggerEventTransactions,
            RunTriggerProperties properties) {
        this.runTriggerEventTransactions = runTriggerEventTransactions;
        this.properties = properties;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        int retentionDays = properties.getEventRetentionDays();
        if (retentionDays <= 0) {
            // A cutoff of now (or the future) would prune every terminal row on the very next
            // tick, destroying diagnostic history for a misconfiguration this job has no
            // business acting on - same reasoning as the lease/batch-size floors elsewhere in
            // this package.
            log.warn("Run trigger event retention days configured to non-positive value ({}), skipping prune",
                    retentionDays);
            return;
        }
        long retentionMillis = retentionDays * 24L * 60 * 60 * 1000L;
        runTriggerEventTransactions.pruneTerminalRowsOlderThan(new Date(System.currentTimeMillis() - retentionMillis));
    }
}
