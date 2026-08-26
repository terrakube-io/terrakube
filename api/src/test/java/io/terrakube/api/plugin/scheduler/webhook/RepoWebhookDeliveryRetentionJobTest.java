package io.terrakube.api.plugin.scheduler.webhook;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;

import io.terrakube.api.plugin.vcs.RepoWebhookDeliveryTransactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RepoWebhookDeliveryRetentionJobTest {

    @Mock
    RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;

    @Test
    void pruneCutoffIsRetentionDaysBeforeNow() throws Exception {
        RepoWebhookDeliveryRetentionJob subject = new RepoWebhookDeliveryRetentionJob(repoWebhookDeliveryTransactions, 30);

        long before = System.currentTimeMillis();
        subject.execute(Mockito.mock(JobExecutionContext.class));
        long after = System.currentTimeMillis();

        ArgumentCaptor<Date> cutoffCaptor = ArgumentCaptor.forClass(Date.class);
        verify(repoWebhookDeliveryTransactions).pruneTerminalRowsOlderThan(cutoffCaptor.capture());

        long expectedMin = before - (30L * 24 * 60 * 60 * 1000);
        long expectedMax = after - (30L * 24 * 60 * 60 * 1000);
        assertThat(cutoffCaptor.getValue().getTime()).isBetween(expectedMin, expectedMax);
    }
}
