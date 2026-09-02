package io.terrakube.api.plugin.scheduler;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartzMetricsTest {

    @Test
    void returnsTheCurrentlyExecutingJobCount() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getCurrentlyExecutingJobs()).thenReturn(java.util.List.of(
                mock(org.quartz.JobExecutionContext.class), mock(org.quartz.JobExecutionContext.class)));

        assertThat(QuartzMetrics.currentlyExecutingCount(scheduler)).isEqualTo(2);
    }

    @Test
    void returnsNegativeOneWhenSchedulerThrows() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getCurrentlyExecutingJobs()).thenThrow(new SchedulerException("down"));

        assertThat(QuartzMetrics.currentlyExecutingCount(scheduler)).isEqualTo(-1);
    }
}
