package io.terrakube.storage.plugin.local;

import io.terrakube.storage.plugin.core.GitService;
import io.terrakube.storage.plugin.core.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "io.terrakube.registry.plugin.storage", name = "type", havingValue = "Local", matchIfMissing = false)
public class LocalStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService localStorageService(GitService gitService,
                                              @Value("${io.terrakube.registry.hostname:${org.openregistry.hostname:http://localhost:8080}}") String registryHostname) {
        log.info("Configuring LocalStorageServiceImpl");
        return LocalStorageServiceImpl.builder()
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();
    }
}
