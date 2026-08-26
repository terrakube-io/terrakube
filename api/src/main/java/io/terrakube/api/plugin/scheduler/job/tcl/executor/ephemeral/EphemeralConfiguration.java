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
    private Map<String, String> nodeSelector;
}
