package io.terrakube.api.plugin.scheduler.notification;

import java.util.Date;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.terrakube.api.plugin.notification.NotificationOutboxTransactions;

import lombok.extern.slf4j.Slf4j;

// Runs far less often than NotificationOutboxPollerJob (daily, not every minute) - this is
// housekeeping, not delivery, so there's no reason to pay a DELETE's cost on the hot dispatch
// cadence. Same reasoning as NotificationOutboxPollerJob for why the actual DB work is delegated
// to NotificationOutboxTransactions rather than done directly here: Quartz Jobs never get an AOP
// proxy of their own, so a @Transactional method declared directly on this class would be a no-op.
@Slf4j
@Component
@DisallowConcurrentExecution
public class NotificationOutboxRetentionJob implements Job {

    private final NotificationOutboxTransactions notificationOutboxTransactions;
    private final long retentionMillis;

    public NotificationOutboxRetentionJob(NotificationOutboxTransactions notificationOutboxTransactions,
            @Value("${io.terrakube.notification.outbox.retentionDays:90}") long retentionDays) {
        this.notificationOutboxTransactions = notificationOutboxTransactions;
        this.retentionMillis = retentionDays * 24 * 60 * 60 * 1000L;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        notificationOutboxTransactions.pruneTerminalRowsOlderThan(new Date(System.currentTimeMillis() - retentionMillis));
    }
}
