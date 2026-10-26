package io.terrakube.executor.service.logs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.executor.logs")
public class ExecutorLogsProperties {

    /**
     * Approximate cap on the per-job live-log Redis stream. A huge apply can't exhaust ElastiCache;
     * the complete log is always persisted to object storage at step end.
     */
    private long redisMaxLen = 200_000L;

    /**
     * Also write every line of a step's output to the executor's own service log, so it can be read with
     * {@code kubectl logs}. Off by default: the output already goes to the job's log and to its saved output,
     * so a copy here is ingested a second time by the cluster's log pipeline, and it lands in a log with
     * different access rules from the job's.
     */
    private boolean echoToServiceLog = false;

    /**
     * Largest line, in bytes, sent to the live stream (Redis, or the API). A longer line is cut with a
     * marker that says how much was left out; the saved output keeps the whole line. Together with
     * {@link #redisMaxLen} this bounds what the live stream can hold. 0 or less turns the limit off.
     */
    private int liveLineMaxBytes = 65_536;

    /**
     * With {@code io.executor.log-via-api=true}: the most bytes of lines the executor keeps while the API
     * cannot take them. Beyond it the oldest lines are dropped from the live view (counted in
     * {@code terrakube.executor.logs.dropped}), never the saved output and never the run. Before, the queue
     * kept growing for as long as the API was away.
     */
    private long apiQueueMaxBytes = 16_777_216L;

    /** With {@code io.executor.log-via-api=true}: the most bytes of lines sent to the API in one request. */
    private long apiBatchMaxBytes = 1_048_576L;

    /**
     * How much of a step's console output is kept in memory for the saved output. Past it the output
     * moves to a temporary file next to the step's working directory, and is uploaded from there when the
     * step ends. 0 or less keeps everything in memory.
     */
    private long stepOutputMemoryBytes = 4L * 1024 * 1024;

    /**
     * Most bytes of a plan's rendered text published to the job context, which is what the pull request
     * comment shows. A longer plan is cut there with a note that says how much was left out; the step's
     * output keeps the whole plan. 0 or less publishes all of it.
     */
    private long renderedPlanContextMaxBytes = 49_152L;

    /**
     * Keep the output of a running step in the object store as numbered parts, so the part written so far
     * survives an executor that dies before the step ends. The output saved when the step ends is the same
     * as without this, and the parts are deleted after it.
     */
    private boolean archiveEnabled = true;

    /** A part is written when this many bytes have not been archived yet. Grows for a very long step. */
    private long archivePartBytes = 1_048_576L;

    /** A part is also written after this long, if there is anything new, however small. */
    private long archiveIntervalSeconds = 30L;

    /** Most parts archived for one step. Past it the writer stops until the step ends. */
    private int archiveMaxParts = 1000;
}
