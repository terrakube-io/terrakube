package io.terrakube.api.plugin.scheduler.webhook;

import java.util.Date;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.terrakube.api.plugin.vcs.RepoWebhookDeliveryTransactions;

import lombok.extern.slf4j.Slf4j;

// Runs far less often than RepoWebhookDeliveryPollerJob (daily, not every minute) - this is
// housekeeping, not delivery, so there's no reason to pay a DELETE's cost on the hot dispatch
// cadence. Same reasoning as RepoWebhookDeliveryPollerJob for why the actual DB work is delegated
// to RepoWebhookDeliveryTransactions rather than done directly here: Quartz Jobs never get an AOP
// proxy of their own, so a @Transactional method declared directly on this class would be a no-op.
@Slf4j
@Component
@DisallowConcurrentExecution
public class RepoWebhookDeliveryRetentionJob implements Job {

    private final RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;
    private final long retentionMillis;

    public RepoWebhookDeliveryRetentionJob(RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions,
            @Value("${io.terrakube.webhook.delivery.retentionDays:30}") long retentionDays) {
        this.repoWebhookDeliveryTransactions = repoWebhookDeliveryTransactions;
        this.retentionMillis = retentionDays * 24 * 60 * 60 * 1000L;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        repoWebhookDeliveryTransactions.pruneTerminalRowsOlderThan(new Date(System.currentTimeMillis() - retentionMillis));
    }
}
