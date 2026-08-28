package io.terrakube.registry.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.registry")
public class OpenRegistryProperties {

    private String hostname;
    private String clientId;
    private String issuerUri;

    private long federatedCacheExpireAfterWrite = 10;
    private long federatedCacheMaximumSize = 1000;
    private long providerManagerCacheExpireAfterWrite = 60;
    private long providerManagerCacheMaximumSize = 100;

    /** Seconds the module version list is kept. This is the delay before a new version is served. */
    private long moduleVersionsCacheTtlSeconds = 600;

    /** Hand written because the registry module has no bean validation provider on its classpath. */
    @PostConstruct
    void validate() {
        if (moduleVersionsCacheTtlSeconds <= 0) {
            throw new IllegalStateException("io.terrakube.registry.moduleVersionsCacheTtlSeconds must be at least 1, "
                    + "got " + moduleVersionsCacheTtlSeconds);
        }
    }
}
