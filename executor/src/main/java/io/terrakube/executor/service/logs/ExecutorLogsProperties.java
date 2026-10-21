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
     * {@code kubectl logs}. On by default, as before. The output already goes to the job's log and to its
     * saved output, so turning this off avoids ingesting it a second time in the cluster's log pipeline.
     */
    private boolean echoToServiceLog = true;

    /**
     * Largest line, in bytes, sent to the live stream (Redis, or the API). A longer line is cut with a
     * marker that says how much was left out; the saved output keeps the whole line. Together with
     * {@link #redisMaxLen} this bounds what the live stream can hold. 0 or less turns the limit off.
     */
    private int liveLineMaxBytes = 65_536;
}
