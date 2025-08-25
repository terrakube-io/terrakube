package io.terrakube.api.plugin.webclient;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "io.terrakube.api.plugin.webclient")
public class WebClientConfigProperties {
    private boolean proxyEnabled;
    private boolean proxyUseTls;
    private String proxyHost;
    private int proxyPort;
    private String proxyUsername;
    private String proxyPassword;
}
