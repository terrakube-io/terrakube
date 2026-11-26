package io.terrakube.executor.configuration;

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
@ConfigurationProperties(prefix = "io.terrakube.executor.infracost")
public class InfracostExecutorProperties {

    public static final String DEFAULT_VERSION = "0.10.50";

    private String defaultVersion = DEFAULT_VERSION;
    private long timeoutSeconds = 180L;
    private String defaultCurrency = "USD";
    private String pricingApiEndpoint = "";
    private boolean enableDashboard = false;
    private int contextMaxBytes = 49152;
    private int maxContextResources = 50;
}
