package io.terrakube.api.plugin.scheduler.trigger;

import java.util.Date;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

// Daily housekeeping, not the hot poll path. DB work delegated to RunTriggerEventTransactions,
// same reasoning as RunTriggerEventPollerJob: a Quartz Job never gets its own AOP proxy.
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
        long retentionMillis = properties.getEventRetentionDays() * 24L * 60 * 60 * 1000L;
        runTriggerEventTransactions.pruneTerminalRowsOlderThan(new Date(System.currentTimeMillis() - retentionMillis));
    }
}
