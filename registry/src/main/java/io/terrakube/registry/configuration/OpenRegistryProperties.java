package io.terrakube.registry.configuration;

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
}
