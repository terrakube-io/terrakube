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
}
