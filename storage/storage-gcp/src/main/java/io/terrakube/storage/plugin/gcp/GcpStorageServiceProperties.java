package io.terrakube.storage.plugin.gcp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.registry.plugin.storage.gcp")
public class GcpStorageServiceProperties {
    private String credentials;
    private String bucketName;
    private String projectId;
}
