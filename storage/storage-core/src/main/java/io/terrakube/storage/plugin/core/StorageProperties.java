package io.terrakube.storage.plugin.core;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.registry.plugin.storage")
public class StorageProperties {
    private StorageType type;
}
