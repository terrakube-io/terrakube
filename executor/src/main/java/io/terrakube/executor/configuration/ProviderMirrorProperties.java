package io.terrakube.executor.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Installs providers through the registry's provider network mirror. See docs/ops/registry-provider-mirror.md.
 */
@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.provider-mirror")
public class ProviderMirrorProperties {

    // Write the mirror block into the job's CLI configuration.
    private boolean enabled = false;
    // Leave out the direct fallback, so a provider that is not in the mirror fails init.
    private boolean only = false;
    // Registry base URL. Empty means https://<TerrakubeRegistryDomain>, or <TerrakubePublicUrl>/api/private-registry.
    private String registryUrl = "";
}
