package io.terrakube.api.plugin.hardened;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.hardened")
public class HardenedModeProperties {

    private boolean enabled;

    private String runnerTokenPrivateKeyPath;

    private String runnerTokenPublicKeyPath;

    private int runnerTokenTtlMinutes = 360;

    private String runnerServiceAccount;

    private String runnerConfigMap;
}
