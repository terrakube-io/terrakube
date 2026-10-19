package io.terrakube.api.plugin.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

import io.terrakube.api.plugin.scheduler.dispatchretry.DispatchRetryProperties;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationProperties;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;

/** ScheduleJobService.wakeQueueHead: the one place that wakes the front of the FIFO dispatch queue. */
class ScheduleJobServiceWakeQueueHeadTest {

    private final Scheduler scheduler = mock(Scheduler.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final ReconciliationProperties reconciliationProperties = new ReconciliationProperties();
    // Deferral guard off by default so the guarded/unguarded tests exercise the plain queries; the
    // dispatch-retry-aware ladder has its own test.
    private final DispatchRetryProperties dispatchRetryProperties = deferralDisabled();

    private static DispatchRetryProperties deferralDisabled() {
        DispatchRetryProperties properties = new DispatchRetryProperties();
        properties.setAdmissionDeferralEnabled(false);
        return properties;
    }

    private ScheduleJobService subject() {
        return new ScheduleJobService(scheduler, mock(StepRepository.class), mock(WorkspaceRepository.class),
                jobRepository, reconciliationProperties, dispatchRetryProperties);
    }

    private Job job(int id) {
        Job job = new Job();
        job.setId(id);
        return job;
    }

    @Test
    void schedulesTheOldestWaitingJob() throws Exception {
        when(jobRepository.findNextDispatchableExecutableJobId()).thenReturn(42);
        when(jobRepository.getReferenceById(42)).thenReturn(job(42));

        subject().wakeQueueHead();

        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void doesNothingWhenNoJobIsWaiting() throws Exception {
        when(jobRepository.findNextDispatchableExecutableJobId()).thenReturn(null);

        subject().wakeQueueHead();

        verify(scheduler, never()).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void usesTheUnguardedQueryWhenTheAdmissionGuardIsDisabled() throws Exception {
        reconciliationProperties.setAdmissionGuardEnabled(false);
        when(jobRepository.findNextDispatchableJobId()).thenReturn(7);
        when(jobRepository.getReferenceById(7)).thenReturn(job(7));

        subject().wakeQueueHead();

        verify(jobRepository, never()).findNextDispatchableExecutableJobId();
        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void usesTheDeferralAwareQueryWhenDispatchRetryAdmissionDeferralIsEnabled() throws Exception {
        dispatchRetryProperties.setAdmissionDeferralEnabled(true);
        when(jobRepository.findNextDispatchableExecutableNotDeferredJobId()).thenReturn(8);
        when(jobRepository.getReferenceById(8)).thenReturn(job(8));

        subject().wakeQueueHead();

        verify(jobRepository, never()).findNextDispatchableExecutableJobId();
        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void neverThrowsWhenTheDatabaseOrSchedulerFails() throws Exception {
        when(jobRepository.findNextDispatchableExecutableJobId()).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> subject().wakeQueueHead()).doesNotThrowAnyException();

        doReturn(9).when(jobRepository).findNextDispatchableExecutableJobId();
        doReturn(job(9)).when(jobRepository).getReferenceById(9);
        when(scheduler.scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class)))
                .thenThrow(new SchedulerException("quartz down"));
        assertThatCode(() -> subject().wakeQueueHead()).doesNotThrowAnyException();
    }
}
