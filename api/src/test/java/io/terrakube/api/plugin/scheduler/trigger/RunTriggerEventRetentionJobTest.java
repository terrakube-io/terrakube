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
        Date before = new Date(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000));

        subject.execute(null);

        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(runTriggerEventTransactions).pruneTerminalRowsOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isBeforeOrEqualTo(before).isAfter(new Date(before.getTime() - 5_000));
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
