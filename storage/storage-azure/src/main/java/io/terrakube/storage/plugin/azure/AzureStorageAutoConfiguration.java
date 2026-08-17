package io.terrakube.storage.plugin.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
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

@Slf4j
@AutoConfiguration
@ConditionalOnClass(BlobServiceClient.class)
@ConditionalOnProperty(prefix = "io.terrakube.registry.plugin.storage", name = "type", havingValue = "AzureStorageImpl")
@EnableConfigurationProperties(AzureStorageServiceProperties.class)
public class AzureStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService azureStorageService(AzureStorageServiceProperties azureStorageServiceProperties,
                                              GitService gitService,
                                              @Value("${io.terrakube.registry.hostname:${org.openregistry.hostname:http://localhost:8080}}") String registryHostname) {
        log.info("Configuring AzureStorageServiceImpl");
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(
                        String.format("DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                                azureStorageServiceProperties.getAccountName(),
                                azureStorageServiceProperties.getAccountKey())
                ).buildClient();

        return AzureStorageServiceImpl.builder()
                .blobServiceClient(blobServiceClient)
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();
    }
}
