package io.terrakube.executor.service.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects a job that never reaches ExecutorJobImpl's finally block (hung terraform process,
 * infinite-looping hook script, etc). Without this, a wedged pod stays REFUSING_TRAFFIC
 * forever with nothing else noticing, silently shrinking cluster capacity.
 */
@Slf4j
@Component
public class JobExecutionWatchdog {

    private final ApplicationEventPublisher eventPublisher;
    private final Duration maxJobDuration;
    private final AtomicReference<Instant> busySince = new AtomicReference<>();

    public JobExecutionWatchdog(ApplicationEventPublisher eventPublisher,
            @Value("${io.terrakube.executor.job.maxDurationMinutes}") long maxDurationMinutes) {
        this.eventPublisher = eventPublisher;
        this.maxJobDuration = Duration.ofMinutes(maxDurationMinutes);
    }

    void markBusy() {
        busySince.set(Instant.now());
    }

    void markFree() {
        busySince.set(null);
    }

    @Scheduled(fixedDelay = 60_000)
    void checkForWedgedJob() {
        Instant since = busySince.get();
        if (since != null && Duration.between(since, Instant.now()).compareTo(maxJobDuration) > 0) {
            log.error("Job has been running for longer than {} without completing, marking pod unhealthy so it gets restarted", maxJobDuration);
            AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);
        }
    }
}
