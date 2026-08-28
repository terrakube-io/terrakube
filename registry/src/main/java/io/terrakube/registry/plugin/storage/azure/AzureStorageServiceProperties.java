package io.terrakube.registry.plugin.storage.azure;

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
@ConfigurationProperties(prefix = "io.terrakube.registry.plugin.storage.azure")
public class AzureStorageServiceProperties {

    private String accountName;
    private String accountKey;

    // How long a presigned module.zip SAS download URL stays valid.
    private int presignedUrlExpirySeconds = 300;
    // When true, the module.zip endpoint redirects to a short-lived SAS URL instead of proxying
    // the blob's bytes through the registry pod. Off by default for staged rollout; also the
    // rollback switch back to the byte-proxy path.
    private boolean presignedRedirectEnabled;
}
