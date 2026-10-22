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

    /** Live bytes buffered per viewer before a slow one is disconnected (it resumes from its cursor). */
    private long sseSubscriberMaxQueuedBytes = 1_048_576L;

    /** Newest job records sent to a viewer with no resume id; 0 replays everything retained. */
    private int sseFreshViewerTailRecords = 5000;

    /** Records per page when replaying history. */
    private int sseReplayPageSize = 1000;

    /** Replay page reads a pod runs at once across all viewers. */
    private int sseMaxConcurrentReplayReads = 16;

    /** Idle time before a keep-alive comment is sent. */
    private Duration sseHeartbeatInterval = Duration.ofSeconds(15);

    /** How long a job's terminal/non-terminal state is cached for the broadcaster loop. */
    private Duration jobStatusCacheTtl = Duration.ofSeconds(2);

    /** Approximate cap on a job's live-log Redis stream when logs are ingested via the API. */
    private long redisMaxLen = 200_000L;
    /** Largest request body, in bytes of output lines, accepted by the log append endpoint; a larger one gets 413. */
    private long appendMaxBytes = 4_194_304L;
    /** Largest line, in bytes, kept in the live stream when logs are ingested via the API; a longer one is cut. */
    private int appendLineMaxBytes = 65_536;
}
