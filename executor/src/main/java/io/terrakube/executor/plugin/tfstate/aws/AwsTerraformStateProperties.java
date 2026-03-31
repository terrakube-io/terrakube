package io.terrakube.executor.plugin.tfstate.aws;

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
@ConfigurationProperties(prefix = "io.terrakube.executor.plugin.tfstate.aws")
public class AwsTerraformStateProperties {

    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String region;
    private String endpoint;
    private boolean includeBackendKeys;
    private boolean enableRoleAuthentication;
    /**
     * When true, enables S3 path-style access (e.g. https://endpoint/bucket/key).
     * Required for S3-compatible storage (MinIO, Wasabi, Backblaze B2, Ceph, etc.).
     * Must NOT be set when using real AWS S3 (AWS deprecated path-style for new buckets).
     * Default: false
     */
    private boolean forcePathStyle;
}
