package io.terrakube.storage.plugin.azure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.registry.plugin.storage.azure")
public class AzureStorageServiceProperties {
    private String accountName;
    private String accountKey;
}
