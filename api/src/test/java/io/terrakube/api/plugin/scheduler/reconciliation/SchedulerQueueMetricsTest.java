package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class SchedulerQueueMetricsTest {

    JobRepository jobRepository;
    MeterRegistry registry;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        registry = new SimpleMeterRegistry();
    }

    @Test
    void exposesDepthAndHeadGauges() {
        doReturn(3).when(jobRepository).countDispatchEligibleJobs();
        doReturn(42).when(jobRepository).findNextDispatchableExecutableJobId();
        Job head = new Job();
        head.setId(42);
        head.setCreatedDate(new Date(System.currentTimeMillis() - 120_000));
        doReturn(Optional.of(head)).when(jobRepository).findById(42);

        SchedulerQueueMetrics metrics = new SchedulerQueueMetrics(jobRepository, registry);
        metrics.registerGauges();

        assertThat(registry.get("terrakube.scheduler.executor.queue.depth").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.job").gauge().value()).isEqualTo(42.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.age.seconds").gauge().value())
                .isBetween(110.0, 130.0);
    }

    @Test
    void emptyQueueReportsZeroDepthAndMinusOneHead() {
        doReturn(0).when(jobRepository).countDispatchEligibleJobs();
        doReturn(null).when(jobRepository).findNextDispatchableExecutableJobId();

        SchedulerQueueMetrics metrics = new SchedulerQueueMetrics(jobRepository, registry);
        metrics.registerGauges();

        assertThat(registry.get("terrakube.scheduler.executor.queue.depth").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.job").gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.age.seconds").gauge().value()).isEqualTo(0.0);
    }
}
