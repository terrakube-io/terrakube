package io.terrakube.executor.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Tuning knobs for the asynchronous structured-output persistence queue. Defaults are conservative;
 * {@code queueCapacity} bounds memory, and the backoff/attempt budget is sized so a transient
 * context-store outage is ridden out without ever failing or blocking a Terraform run.
 */
@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.executor.structured-output")
public class StructuredOutputProperties {

    private int queueCapacity = 256;
    // Estimated bytes the snapshots waiting to be persisted may hold together. When a new one does not
    // fit, the oldest progress snapshots go first and a final one only after them. A single snapshot is
    // always accepted, however large, so the final output of a step is not refused. 0 or less turns it off.
    private long queueMaxBytes = 67_108_864L;
    private int maxPersistAttempts = 4;
    private long initialBackoffMs = 250;
    private long maxBackoffMs = 4000;
    private long drainTimeoutMs = 30000;

    // Delayed recovery: after the normal retry budget is exhausted, the newest snapshot per
    // (jobId, stepId, phase) is retained and retried on a slow scheduled cadence until it persists
    // or the retention period expires.
    private long recoveryRetentionMs = 600_000;
    private long recoveryInitialDelayMs = 15_000;
    private long recoveryMaxDelayMs = 60_000;
    private int recoveryMaxRetained = 64;
    // The same budget for the snapshots retained for delayed recovery.
    private long recoveryMaxRetainedBytes = 67_108_864L;
}
