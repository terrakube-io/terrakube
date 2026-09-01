package io.terrakube.api.plugin.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Tuning knobs for the {@code /context/v1} read path: a short per-pod cache in front of object
 * storage, single-flight coalescing of concurrent misses, and an overall read timeout applied
 * independently of the shared S3 client timeouts.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.context")
public class ContextProperties {

    /** How long a successfully read context stays cached per API pod. */
    private Duration cacheTtl = Duration.ofSeconds(30);

    /** Maximum number of distinct job contexts held in the per-pod cache. */
    private int cacheMaxEntries = 500;

    /** Overall budget for a single object-store context read before returning a controlled 503. */
    private Duration readTimeout = Duration.ofSeconds(8);

    /** Retry hint (seconds) returned to clients when context is temporarily unavailable. */
    private int retryAfterSeconds = 5;
}
