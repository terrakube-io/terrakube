package io.terrakube.executor.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Limits for the plan artifacts bundle (the files a plan step hands to its apply step). They bound
 * how much an executor pod reads, writes and unpacks, so a pattern that matches far more than
 * intended fails the plan with a clear message instead of filling the pod's disk or the bucket.
 */
@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.executor.artifacts")
public class ArtifactProperties {

    /** Largest total size of the files in a bundle, and of what is unpacked from one. */
    private long maxBytes = 512L * 1024 * 1024;

    /** Largest number of files in a bundle. */
    private int maxFiles = 10_000;
}
