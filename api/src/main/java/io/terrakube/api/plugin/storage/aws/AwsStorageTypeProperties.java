package io.terrakube.api.plugin.storage.aws;

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
@ConfigurationProperties(prefix = "io.terrakube.storage.aws")
public class AwsStorageTypeProperties {
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String region;
    private String endpoint;
    private boolean enableRoleAuthentication;

    // Storage read resilience: a hung object read fails within apiCallTimeout instead of the SDK's
    // multi-minute default retry ladder. Step-log objects are KB-sized; sub-second is normal.
    private int apiCallTimeoutSeconds = 10;
    private int apiCallAttemptTimeoutSeconds = 3;
    private int maxRetryAttempts = 2;

    // S3-compatible backends (Qumulo, MinIO, ...) may require a real signing region instead of
    // "auto" and may not support chunked transfer encoding or checksum validation. Defaults keep
    // the previous behavior for custom endpoints.
    private String endpointRegion = "auto";
    private boolean chunkedEncodingEnabled = true;
    private boolean checksumValidationEnabled = true;
}
