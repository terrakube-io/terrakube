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
    private boolean useLockfile;

    // S3-compatible backends (Qumulo, MinIO, ...) may require a real signing region instead of
    // "auto" and may not support chunked transfer encoding or checksum validation. Defaults keep
    // the previous behavior for custom endpoints.
    private String endpointRegion = "auto";
    private boolean chunkedEncodingEnabled = true;
    private boolean checksumValidationEnabled = true;
}
