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
}
