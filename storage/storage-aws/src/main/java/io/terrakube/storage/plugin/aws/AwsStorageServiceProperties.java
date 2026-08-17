package io.terrakube.storage.plugin.aws;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.registry.plugin.storage.aws")
public class AwsStorageServiceProperties {
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String region;
    private String endpoint;
    private boolean enableRoleAuthentication;
}
