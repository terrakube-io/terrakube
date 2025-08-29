package io.terrakube.executor.configuration;

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
@ConfigurationProperties(prefix = "io.terrakube.executor.redis")
public class RedisProperties {
    private String hostname;
    private Integer port;
    private String truststorePassword;
    private Sentinel sentinel;
    private Cluster cluster;
    private String username;
    private String password;
    private boolean ssl;
    private String truststorePath;

    @Getter
    @Setter
    public static class Cluster {
        private Integer maxRedirects;
        private List<String> nodes;
    }
    @Getter
    @Setter
    public static class Sentinel {
        private String username;
        private String master;
        private String password;
        private List<String> nodes;
    }
}
