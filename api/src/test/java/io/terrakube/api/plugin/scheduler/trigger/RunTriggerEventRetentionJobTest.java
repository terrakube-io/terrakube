package io.terrakube.api.plugin.scheduler.trigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RunTriggerEventRetentionJobTest {

    RunTriggerEventTransactions runTriggerEventTransactions;
    RunTriggerProperties properties;
    RunTriggerEventRetentionJob subject;

    @BeforeEach
    void setup() {
        runTriggerEventTransactions = mock(RunTriggerEventTransactions.class);
        properties = new RunTriggerProperties();
        subject = new RunTriggerEventRetentionJob(runTriggerEventTransactions, properties);
    }

    @Test
    void prunesRowsOlderThanTheConfiguredRetention() throws Exception {
        properties.setEventRetentionDays(30);
        long retentionMillis = 30L * 24 * 60 * 60 * 1000;
        // The job reads the clock during execute(), so its cutoff is between 30 days before the call
        // started and 30 days before it returned.
        Date earliest = new Date(System.currentTimeMillis() - retentionMillis);

        subject.execute(null);

        Date latest = new Date(System.currentTimeMillis() - retentionMillis);
        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(runTriggerEventTransactions).pruneTerminalRowsOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(earliest, latest, true, true);
    }

    /**
     * A retention of zero or less would compute a cutoff of now or the future, deleting every
     * terminal row on the next tick. That is a misconfiguration to skip loudly, not act on.
     */
    @Test
    void zeroRetentionDaysSkipsThePruneInsteadOfDeletingEverything() throws Exception {
        properties.setEventRetentionDays(0);

        subject.execute(null);

        verify(runTriggerEventTransactions, never()).pruneTerminalRowsOlderThan(any());
    }

    @Test
    void negativeRetentionDaysSkipsThePrune() throws Exception {
        properties.setEventRetentionDays(-5);

        subject.execute(null);

        verify(runTriggerEventTransactions, never()).pruneTerminalRowsOlderThan(any());
    }
}
