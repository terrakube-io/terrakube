package io.terrakube.api.plugin.streaming;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.api.redis")
public class StreamingProperties {
    private String hostname;
    private Integer port;
    private String username;
    private String password;
    private boolean ssl;
    private String truststorePath;
    private String truststorePassword;
    private Sentinel sentinel;
    private Cluster cluster;

    @Getter
    @Setter
    public static class Sentinel {
        private String master;
        private List<String> nodes;
        private String username;
        private String password;
    }

    @Getter
    @Setter
    public static class Cluster {
        private List<String> nodes;
        private Integer maxRedirects;
    }
}
