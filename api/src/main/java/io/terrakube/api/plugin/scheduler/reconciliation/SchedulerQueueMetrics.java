package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.api.plugin.scheduler.dispatchretry.DispatchRetryProperties;
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
    private final DispatchRetryProperties dispatchRetryProperties;

    public SchedulerQueueMetrics(JobRepository jobRepository, MeterRegistry meterRegistry,
            DispatchRetryProperties dispatchRetryProperties) {
        this.jobRepository = jobRepository;
        this.meterRegistry = meterRegistry;
        this.dispatchRetryProperties = dispatchRetryProperties;
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
        Gauge.builder("terrakube.scheduler.executor.queue.dispatch.deferred", this,
                        SchedulerQueueMetrics::dispatchDeferredCount)
                .description("Pending/approved jobs currently excluded from dispatch purely by dispatch-retry backoff")
                .register(meterRegistry);
    }

    // These gauges already always use the guarded (step-aware) query regardless of
    // ReconciliationProperties.admissionGuardEnabled - a pre-existing, deliberate simplification
    // for an observability-only read. Passing admissionGuardEnabled=true here keeps that same
    // simplification while reusing the one shared "is deferral active" computation, so once
    // admissionDeferralEnabled is on, a job merely parked in backoff doesn't read here as a
    // stuck-queue-head false alarm.
    private boolean useNotDeferredQuery() {
        return dispatchRetryProperties.isAdmissionDeferralActive(true);
    }

    static double headJobId(SchedulerQueueMetrics self) {
        Integer id = self.useNotDeferredQuery()
                ? self.jobRepository.findNextDispatchableExecutableNotDeferredJobId()
                : self.jobRepository.findNextDispatchableExecutableJobId();
        return id == null ? -1 : id;
    }

    static double headAgeSeconds(SchedulerQueueMetrics self) {
        Integer id = self.useNotDeferredQuery()
                ? self.jobRepository.findNextDispatchableExecutableNotDeferredJobId()
                : self.jobRepository.findNextDispatchableExecutableJobId();
        if (id == null) {
            return 0;
        }
        return self.jobRepository.findById(id)
                .map(Job::getCreatedDate)
                .map(created -> Math.max(0, (System.currentTimeMillis() - created.getTime()) / 1000.0))
                .orElse(0.0);
    }

    static double dispatchDeferredCount(SchedulerQueueMetrics self) {
        return Math.max(0, self.jobRepository.countDispatchEligibleJobs()
                - self.jobRepository.countDispatchEligibleNotDeferredJobs());
    }
}
