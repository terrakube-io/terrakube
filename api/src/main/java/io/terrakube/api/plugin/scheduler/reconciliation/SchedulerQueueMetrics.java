package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Queue-liveness gauges for the shared executor pool. Alert when the head age rises while
 * {@code quartz.jobs.executing} is ~0 - idle executors with a blocked queue (design doc §3.9).
 */
@Component
public class SchedulerQueueMetrics {

    private final JobRepository jobRepository;
    private final MeterRegistry meterRegistry;

    public SchedulerQueueMetrics(JobRepository jobRepository, MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("terrakube.scheduler.executor.queue.depth", jobRepository,
                        JobRepository::countDispatchEligibleJobs)
                .description("Jobs currently eligible for the shared executor pool (guarded FIFO query)")
                .register(meterRegistry);
        Gauge.builder("terrakube.scheduler.executor.queue.head.job", this, SchedulerQueueMetrics::headJobId)
                .description("Numeric id of the eligible FIFO head job, -1 if the queue is empty")
                .register(meterRegistry);
        Gauge.builder("terrakube.scheduler.executor.queue.head.age.seconds", this,
                        SchedulerQueueMetrics::headAgeSeconds)
                .description("Age in seconds of the eligible FIFO head job, 0 if the queue is empty")
                .register(meterRegistry);
    }

    static double headJobId(SchedulerQueueMetrics self) {
        Integer id = self.jobRepository.findNextDispatchableExecutableJobId();
        return id == null ? -1 : id;
    }

    static double headAgeSeconds(SchedulerQueueMetrics self) {
        Integer id = self.jobRepository.findNextDispatchableExecutableJobId();
        if (id == null) {
            return 0;
        }
        return self.jobRepository.findById(id)
                .map(Job::getCreatedDate)
                .map(created -> Math.max(0, (System.currentTimeMillis() - created.getTime()) / 1000.0))
                .orElse(0.0);
    }
}
