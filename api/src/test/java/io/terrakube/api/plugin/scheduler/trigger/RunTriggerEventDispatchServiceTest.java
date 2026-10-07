package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RunTriggerEventDispatchServiceTest {

    RunTriggerEventTransactions runTriggerEventTransactions;
    RunTriggerDispatchService runTriggerDispatchService;
    RunTriggerProperties properties;
    RunTriggerEventMetrics metrics;
    RunTriggerEventDispatchService subject;

    @BeforeEach
    void setup() {
        runTriggerEventTransactions = mock(RunTriggerEventTransactions.class);
        runTriggerDispatchService = mock(RunTriggerDispatchService.class);
        properties = new RunTriggerProperties();
        metrics = mock(RunTriggerEventMetrics.class);
        subject = new RunTriggerEventDispatchService(runTriggerEventTransactions, runTriggerDispatchService,
                properties, metrics);
    }

    private ClaimedEvent claimed(int jobId, int attemptCount, Date lastAttemptAt) {
        return new ClaimedEvent(jobId, attemptCount, lastAttemptAt);
    }

    @Test
    void anUnclaimableEventIsLeftAlone() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(null).when(runTriggerEventTransactions).claim(id);

        subject.process(id);

        verify(runTriggerDispatchService, never()).dispatchInternal(any(Integer.class));
        verify(runTriggerEventTransactions, never()).recordResult(any(), any(), any(), any(), any());
    }

    @Test
    void successfulDispatchRecordsProcessed() throws Exception {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        doReturn(claimed(900, 1, lastAttemptAt)).when(runTriggerEventTransactions).claim(id);

        subject.process(id);

        verify(runTriggerDispatchService).dispatchInternal(900);
        verify(runTriggerEventTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(RunTriggerEventStatus.PROCESSED), isNull(), isNull());
        verify(metrics).processed();
    }

    @Test
    void aFailureUnderTheAttemptLimitIsRescheduledAsPendingWithABackoffDelay() throws Exception {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        properties.setEventMaxAttempts(10);
        doReturn(claimed(900, 3, lastAttemptAt)).when(runTriggerEventTransactions).claim(id);
        doThrow(new IllegalStateException("boom")).when(runTriggerDispatchService).dispatchInternal(900);

        subject.process(id);

        ArgumentCaptor<Date> nextAttemptAt = ArgumentCaptor.forClass(Date.class);
        verify(runTriggerEventTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(RunTriggerEventStatus.PENDING), eq("boom"), nextAttemptAt.capture());
        assertThat(nextAttemptAt.getValue()).isAfter(lastAttemptAt);
        verify(metrics).retried();
    }

    @Test
    void aFailureAtTheAttemptLimitIsRecordedAsPermanentlyFailed() throws Exception {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        properties.setEventMaxAttempts(10);
        doReturn(claimed(900, 10, lastAttemptAt)).when(runTriggerEventTransactions).claim(id);
        doThrow(new IllegalStateException("boom")).when(runTriggerDispatchService).dispatchInternal(900);

        subject.process(id);

        verify(runTriggerEventTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(RunTriggerEventStatus.FAILED), eq("boom"), isNull());
        verify(metrics).failed();
    }

    @Test
    void anExceptionWithNoMessageUsesTheExceptionClassNameInstead() throws Exception {
        UUID id = UUID.randomUUID();
        Date lastAttemptAt = new Date();
        properties.setEventMaxAttempts(10);
        doReturn(claimed(900, 10, lastAttemptAt)).when(runTriggerEventTransactions).claim(id);
        doThrow(new IllegalStateException()).when(runTriggerDispatchService).dispatchInternal(900);

        subject.process(id);

        verify(runTriggerEventTransactions).recordResult(eq(id), eq(lastAttemptAt),
                eq(RunTriggerEventStatus.FAILED), eq("IllegalStateException"), isNull());
    }

    /** process() itself must never throw - it is called once per row in a batch loop. */
    @Test
    void processNeverThrowsEvenWhenRecordingTheResultFails() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new RuntimeException("database gone")).when(runTriggerEventTransactions).claim(id);

        subject.process(id);
    }
}
