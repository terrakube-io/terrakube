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

    /** When true, .well-known login.v1 points at the Terrakube API OAuth broker instead of Dex. */
    private boolean loginBrokerEnabled = false;
    /** Absolute base URL of the Terrakube API; required when loginBrokerEnabled is true. */
    private String loginApiUrl;

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
        if (loginBrokerEnabled && (loginApiUrl == null || loginApiUrl.isBlank())) {
            throw new IllegalStateException("io.terrakube.registry.login-broker-enabled=true requires "
                    + "io.terrakube.registry.login-api-url");
        }
        if (loginApiUrl != null && loginApiUrl.endsWith("/")) {
            loginApiUrl = loginApiUrl.substring(0, loginApiUrl.length() - 1);
        }
    }
}
