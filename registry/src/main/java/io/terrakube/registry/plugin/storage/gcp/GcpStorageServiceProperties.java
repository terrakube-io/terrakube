package io.terrakube.registry.plugin.storage.gcp;

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
@ConfigurationProperties(prefix = "io.terrakube.registry.plugin.storage.gcp")
public class GcpStorageServiceProperties {
    private String credentials;
    private String bucketName;
    private String projectId;

    // How long a presigned module.zip signed URL stays valid.
    private int presignedUrlExpirySeconds = 300;
    // When true, the module.zip endpoint redirects to a short-lived V4-signed GCS URL instead of
    // proxying the object's bytes through the registry pod. Off by default for staged rollout; also
    // the rollback switch back to the byte-proxy path.
    // Note: the URL is signed with the credentials' service account. A service account key signs
    // locally; Application Default Credentials (for example GKE Workload Identity bound to a Google
    // service account) sign through IAM and need iam.serviceAccounts.signBlob on that service account.
    private boolean presignedRedirectEnabled;
}
