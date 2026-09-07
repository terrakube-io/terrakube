package io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Getter
@Setter
@PropertySources({
        @PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true),
        @PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
})
@ConfigurationProperties(prefix = "io.terrakube.executor.ephemeral")
public class EphemeralConfiguration {

    private String namespace;
    private String image;
    private List<String> secret;

    // Pod scheduling defaults. Job-level node selector overrides remain supported separately.
    private Map<String, String> nodeSelector;
    private String serviceAccount;
    private String tolerations;
    private String annotations;
    private String labels;

    // Shared ConfigMap configuration.
    private ConfigMap configMap = new ConfigMap();

    // Resource requests and limits for ephemeral pods.
    private Resources resources = new Resources();

    // Optional advanced pod configuration.
    private String podAnnotations;
    private String podSecurityContext;
    private String securityContext;
    private String jobEnvVars;

    @Getter
    @Setter
    public static class ConfigMap {
        private String envFrom;
        private String name;
        private String mountPath;
    }

    @Getter
    @Setter
    public static class Resources {
        private String cpuRequest;
        private String cpuLimit;
        private String memoryRequest;
        private String memoryLimit;
        private String ephemeralStorageRequest;
        private String ephemeralStorageLimit;
    }
}
