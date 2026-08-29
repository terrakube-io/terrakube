package io.terrakube.api.plugin.logs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.logs")
public class LogsProperties {

    /** Total weight (bytes) the terminal-step log cache may hold. */
    private long cacheMaxWeightBytes = 268_435_456L;

    /** How long a cached terminal-step log stays resident. */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /** Objects larger than this are streamed through uncached. */
    private long cacheableMaxObjectBytes = 1_048_576L;

    /** Max concurrent step-log SSE connections a single pod will hold before returning 503. */
    private int sseMaxConnections = 1500;

    /** Redis XREAD block duration for a job's shared broadcaster loop. */
    private Duration sseJobIdleTimeout = Duration.ofSeconds(2);

    /** A step-log SSE stream is closed after this long; the client reconnects with Last-Event-ID. */
    private Duration sseMaxStreamDuration = Duration.ofMinutes(30);

    /** How long a job's terminal/non-terminal state is cached for the broadcaster loop. */
    private Duration jobStatusCacheTtl = Duration.ofSeconds(2);

    /** Approximate cap on a job's live-log Redis stream when logs are ingested via the API. */
    private long redisMaxLen = 200_000L;
}
