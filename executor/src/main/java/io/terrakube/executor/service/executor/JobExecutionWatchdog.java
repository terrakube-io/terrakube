package io.terrakube.executor.service.executor;

import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.status.UpdateJobStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects a job that never reaches ExecutorJobImpl's finally block (hung terraform process,
 * infinite-looping hook script, etc). Without this, a wedged pod stays REFUSING_TRAFFIC
 * forever with nothing else noticing, silently shrinking cluster capacity.
 *
 * Also owns two pieces of the api's stuck-job recovery: a Redis heartbeat refreshed while a job
 * is in flight, so the api can tell "still working" from "the executor is gone" - and an
 * immediate job-failure callback on SIGTERM, so a routine rollout/scale-down doesn't leave its
 * in-flight job stuck until the heartbeat times out.
 */
@Slf4j
@Component
public class JobExecutionWatchdog {

    // Duplicated as a literal in api's JobReconciliationSweep - separate Spring Boot apps, no
    // shared module for this constant.
    private static final String HEARTBEAT_PREFIX = "executor-job-heartbeat:";
    private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(45);

    private final ApplicationEventPublisher eventPublisher;
    private final Duration maxJobDuration;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UpdateJobStatus updateJobStatus;
    private final AtomicReference<Instant> busySince = new AtomicReference<>();
    private final AtomicReference<TerraformJob> currentJob = new AtomicReference<>();

    public JobExecutionWatchdog(ApplicationEventPublisher eventPublisher,
            @Value("${io.terrakube.executor.job.maxDurationMinutes}") long maxDurationMinutes,
            RedisTemplate<String, Object> redisTemplate, UpdateJobStatus updateJobStatus) {
        this.eventPublisher = eventPublisher;
        this.maxJobDuration = Duration.ofMinutes(maxDurationMinutes);
        this.redisTemplate = redisTemplate;
        this.updateJobStatus = updateJobStatus;
    }

    void markBusy(TerraformJob job) {
        busySince.set(Instant.now());
        currentJob.set(job);
        refreshHeartbeat();
    }

    void markFree() {
        busySince.set(null);
        TerraformJob job = currentJob.getAndSet(null);
        if (job != null) {
            deleteHeartbeat(job.getJobId());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void checkForWedgedJob() {
        Instant since = busySince.get();
        if (since != null && Duration.between(since, Instant.now()).compareTo(maxJobDuration) > 0) {
            log.error("Job has been running for longer than {} without completing, marking pod unhealthy so it gets restarted", maxJobDuration);
            AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);
        }
    }

    // Runs on Spring's own scheduled-task thread, never the thread doing the actual terraform
    // work - a legitimately slow apply must never be able to starve its own heartbeat.
    @Scheduled(fixedDelay = 15_000)
    void refreshHeartbeat() {
        TerraformJob job = currentJob.get();
        if (job == null) {
            return;
        }
        try {
            if (redisTemplate != null && redisTemplate.opsForValue() != null) {
                redisTemplate.opsForValue().set(HEARTBEAT_PREFIX + job.getJobId(), "1", HEARTBEAT_TTL);
            }
        } catch (DataAccessException e) {
            log.warn("Could not refresh heartbeat for Job {}: {}", job.getJobId(), e.getMessage());
        }
    }

    private void deleteHeartbeat(String jobId) {
        try {
            redisTemplate.delete(HEARTBEAT_PREFIX + jobId);
        } catch (DataAccessException e) {
            // The heartbeat's own TTL self-heals this; nothing else to do here.
            log.warn("Could not delete heartbeat for Job {}: {}", jobId, e.getMessage());
        }
    }

    // Fires on SIGTERM (rollout, scale-down, manual delete) via Spring Boot's JVM shutdown hook,
    // before terminationGracePeriodSeconds elapses and Kubernetes SIGKILLs the process.
    // Best-effort - the api's heartbeat-timeout sweep is the backstop if this gets interrupted.
    @PreDestroy
    void onShutdown() {
        TerraformJob job = currentJob.get();
        if (job == null) {
            return;
        }
        log.warn("Executor pod is shutting down with Job {} in flight, failing it so the queue can proceed", job.getJobId());
        updateJobStatus.setCompletedStatus(false, false, -1, job,
                "Executor pod is shutting down\n", "Executor pod is shutting down", null, "");
    }
}
