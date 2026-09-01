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
    private int maxPersistAttempts = 4;
    private long initialBackoffMs = 250;
    private long maxBackoffMs = 4000;
    private long drainTimeoutMs = 30000;
}
