package io.terrakube.storage.plugin.gcp;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.terrakube.storage.plugin.core.GitService;
import io.terrakube.storage.plugin.core.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

@Slf4j
@AutoConfiguration
@ConditionalOnClass(Storage.class)
@ConditionalOnProperty(prefix = "io.terrakube.registry.plugin.storage", name = "type", havingValue = "GcpStorageImpl")
@EnableConfigurationProperties(GcpStorageServiceProperties.class)
public class GcpStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService gcpStorageService(GcpStorageServiceProperties gcpStorageServiceProperties,
                                            GitService gitService,
                                            @Value("${io.terrakube.registry.hostname:${org.openregistry.hostname:http://localhost:8080}}") String registryHostname) {
        log.info("Configuring GcpStorageServiceImpl");
        try {
            log.info("Credentials Length: {}", gcpStorageServiceProperties.getCredentials() != null ? gcpStorageServiceProperties.getCredentials().length() : 0);
            log.info("GCP Project: {}", gcpStorageServiceProperties.getProjectId());
            log.info("GCP Bucket: {}", gcpStorageServiceProperties.getBucketName());

            Credentials gcpCredentials = GoogleCredentials
                    .fromStream(
                            new ByteArrayInputStream(
                                    Base64.getDecoder().decode(gcpStorageServiceProperties.getCredentials()))
                    );
            Storage gcpStorage = StorageOptions.newBuilder()
                    .setCredentials(gcpCredentials)
                    .setProjectId(gcpStorageServiceProperties.getProjectId())
                    .build()
                    .getService();

            log.info("GCP Storage null: {}", gcpStorage == null);
            return GcpStorageServiceImpl.builder()
                    .bucketName(gcpStorageServiceProperties.getBucketName())
                    .storage(gcpStorage)
                    .gitService(gitService)
                    .registryHostname(registryHostname)
                    .build();
        } catch (IOException e) {
            log.error("Failed to initialize GCP Storage: {}", e.getMessage(), e);
            throw new RuntimeException("Error initializing GCP Storage", e);
        }
    }
}
