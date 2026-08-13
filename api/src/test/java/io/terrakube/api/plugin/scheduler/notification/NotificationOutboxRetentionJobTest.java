package io.terrakube.api.plugin.scheduler.notification;

import io.terrakube.api.plugin.notification.NotificationOutboxTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxRetentionJobTest {

    @Mock
    NotificationOutboxTransactions notificationOutboxTransactions;
    @Mock
    JobExecutionContext jobExecutionContext;

    NotificationOutboxRetentionJob subject;

    @BeforeEach
    void setUp() {
        // Constructed directly rather than via @InjectMocks: retentionDays only resolves via
        // @Value in a real Spring context - see NotificationOutboxPollerJobTest for the same
        // reasoning.
        subject = new NotificationOutboxRetentionJob(notificationOutboxTransactions, 90);
    }

    @Test
    void prunesRowsOlderThanTheConfiguredRetentionWindow() throws Exception {
        subject.execute(jobExecutionContext);

        ArgumentCaptor<Date> cutoffCaptor = ArgumentCaptor.forClass(Date.class);
        verify(notificationOutboxTransactions).pruneTerminalRowsOlderThan(cutoffCaptor.capture());

        long expectedCutoffMillis = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000;
        assertThat(cutoffCaptor.getValue().getTime()).isCloseTo(expectedCutoffMillis, within(5000L));
    }
}
