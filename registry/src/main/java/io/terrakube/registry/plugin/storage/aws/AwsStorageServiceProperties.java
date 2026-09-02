package io.terrakube.registry.plugin.storage.aws;

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
@ConfigurationProperties(prefix = "io.terrakube.registry.plugin.storage.aws")
public class AwsStorageServiceProperties {
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String region;
    private String endpoint;
    private boolean enableRoleAuthentication;

    // Bounds on the S3Client/S3Presigner HTTP client, so a slow or unreachable S3 endpoint fails
    // fast instead of queuing registry request threads indefinitely.
    private int connectionAcquisitionTimeoutSeconds = 1;
    private int connectTimeoutSeconds = 2;
    private int socketTimeoutSeconds = 10;
    private int apiCallAttemptTimeoutSeconds = 15;
    private int apiCallTimeoutSeconds = 25;
    private int maxConnections = 50;

    // How long a presigned module.zip download URL stays valid.
    private int presignedUrlExpirySeconds = 300;
    // When true, the module.zip endpoint redirects to a presigned S3 URL instead of proxying the
    // object's bytes through the registry pod. Off by default for staged rollout; also doubles as
    // the rollback switch back to the byte-proxy path.
    private boolean presignedRedirectEnabled;

    // S3-compatible backends (Qumulo, MinIO, ...) may require a real signing region instead of
    // "auto" and may not support chunked transfer encoding or checksum validation. Defaults keep
    // the previous behavior for custom endpoints.
    private String endpointRegion = "auto";
    private boolean chunkedEncodingEnabled = true;
    private boolean checksumValidationEnabled = true;
}
