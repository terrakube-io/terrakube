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
}
