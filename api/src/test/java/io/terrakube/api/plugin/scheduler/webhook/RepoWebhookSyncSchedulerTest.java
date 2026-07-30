package io.terrakube.api.plugin.scheduler.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;

@ExtendWith(MockitoExtension.class)
class RepoWebhookSyncSchedulerTest {

    @Mock
    Scheduler scheduler;

    @InjectMocks
    RepoWebhookSyncScheduler subject;

    @Test
    void jobKeyIsDeterministicPerUrl() {
        JobKey a = RepoWebhookSyncScheduler.jobKeyFor("https://github.com/owner/repo");
        JobKey b = RepoWebhookSyncScheduler.jobKeyFor("https://github.com/owner/repo");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void jobKeyDiffersPerUrl() {
        JobKey a = RepoWebhookSyncScheduler.jobKeyFor("https://github.com/owner/repo-one");
        JobKey b = RepoWebhookSyncScheduler.jobKeyFor("https://github.com/owner/repo-two");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void schedulesJobUnderTheDeterministicKey() throws SchedulerException {
        subject.scheduleSync("https://github.com/owner/repo", "ws-1");

        verify(scheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void swallowsObjectAlreadyExistsExceptionAsCoalescing() throws SchedulerException {
        // A concurrent caller already scheduled the sync for this exact URL
        // — that's the coalescing working as designed, not a failure this
        // caller should propagate.
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenThrow(new ObjectAlreadyExistsException("already scheduled"));

        assertThatCode(() -> subject.scheduleSync("https://github.com/owner/repo", "ws-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsJobPersistenceExceptionAsRaceLossCoalescing() throws SchedulerException {
        // Quartz's ObjectAlreadyExistsException pre-check isn't atomic under
        // real concurrent transactions (proven by
        // RepoWebhookSyncCoalescingIntegrationTest against real Postgres) —
        // the loser of that race can surface a raw JobPersistenceException
        // instead. That must be coalesced too, not propagated.
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenThrow(new JobPersistenceException("duplicate key value violates unique constraint"));

        assertThatCode(() -> subject.scheduleSync("https://github.com/owner/repo", "ws-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void wrapsOtherSchedulerExceptions() throws SchedulerException {
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenThrow(new SchedulerException("boom"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> subject.scheduleSync("https://github.com/owner/repo", "ws-1"));
        verify(scheduler, never()).triggerJob(any());
    }
}
