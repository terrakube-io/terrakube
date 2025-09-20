package io.terrakube.executor.plugin.tfstate.consul;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.executor.plugin.tfstate.consul")
public class ConsulTerraformStateProperties {
    private String address;
    private String scheme = "http";
    private String path = "terraform";
    private String token;
    private String datacenter;
    private boolean gzip = true;
    private boolean lock = true;
    
    // Vault integration (optional)
    private String vaultAddress;
    private String vaultToken;
    private String vaultTokenPath = "consul/creds/terraform";
    private boolean useVaultForToken = false;
}
