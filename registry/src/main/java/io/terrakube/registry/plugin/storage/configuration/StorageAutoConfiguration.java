package io.terrakube.registry.plugin.storage.configuration;


import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import io.terrakube.registry.configuration.OpenRegistryProperties;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.plugin.storage.aws.AwsStorageServiceImpl;
import io.terrakube.registry.plugin.storage.aws.AwsStorageServiceProperties;
import io.terrakube.registry.plugin.storage.azure.AzureStorageServiceImpl;
import io.terrakube.registry.plugin.storage.azure.AzureStorageServiceProperties;
import io.terrakube.registry.plugin.storage.gcp.GcpStorageServiceImpl;
import io.terrakube.registry.plugin.storage.gcp.GcpStorageServiceProperties;
import io.terrakube.registry.plugin.storage.local.LocalStorageServiceImpl;
import io.terrakube.registry.service.git.GitServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointParams;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

@Configuration
@EnableConfigurationProperties({
        AzureStorageServiceProperties.class,
        StorageProperties.class,
        OpenRegistryProperties.class,
        AwsStorageServiceProperties.class,
        GcpStorageServiceProperties.class
})
@ConditionalOnMissingBean(StorageService.class)
@Slf4j
public class StorageAutoConfiguration {

    @Bean
    public StorageService terraformOutput(OpenRegistryProperties openRegistryProperties, StorageProperties storageProperties, AzureStorageServiceProperties azureStorageServiceProperties, AwsStorageServiceProperties awsStorageServiceProperties, GcpStorageServiceProperties gcpStorageServiceProperties) {
        StorageService storageService = null;
        log.info("StorageType={}", storageProperties.getType());
        switch (storageProperties.getType()) {
            case AzureStorageImpl:
                BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                        .connectionString(
                                String.format("DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                                        azureStorageServiceProperties.getAccountName(),
                                        azureStorageServiceProperties.getAccountKey())
                        ).buildClient();

                storageService = AzureStorageServiceImpl.builder()
                        .blobServiceClient(blobServiceClient)
                        .gitService(new GitServiceImpl())
                        .registryHostname(openRegistryProperties.getHostname())
                        .build();
                break;
            case AwsStorageImpl:
                S3Client s3client;
                if (awsStorageServiceProperties.getEndpoint() != null && !awsStorageServiceProperties.getEndpoint().isEmpty()) {
                    log.info("Creating AWS SDK with custom endpoint and custom credentials");
                    s3client = S3Client.builder()
                            .region(Region.of("auto"))
                            .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsStorageServiceProperties)))
                            .endpointProvider(new S3EndpointProvider() {
                                @Override
                                public CompletableFuture<Endpoint> resolveEndpoint(S3EndpointParams endpointParams) {
                                    return CompletableFuture.completedFuture(Endpoint.builder()
                                            .url(URI.create(awsStorageServiceProperties.getEndpoint() + "/" + endpointParams.bucket()))
                                            .build());
                                }
                            })
                            .build();

                } else {
                    if (awsStorageServiceProperties.isEnableRoleAuthentication()) {
                        log.info("Creating AWS SDK with default credentials");
                        s3client = S3Client.builder()
                                .region(Region.of(awsStorageServiceProperties.getRegion()))
                                .credentialsProvider(DefaultCredentialsProvider.create())
                                .build();
                    } else {
                        log.info("Creating AWS SDK with custom credentials");
                        s3client = S3Client.builder()
                                .region(Region.of(awsStorageServiceProperties.getRegion()))
                                .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsStorageServiceProperties)))
                                .build();

                    }
                }

                storageService = AwsStorageServiceImpl.builder()
                        .s3client(s3client)
                        .gitService(new GitServiceImpl())
                        .bucketName(awsStorageServiceProperties.getBucketName())
                        .registryHostname(openRegistryProperties.getHostname())
                        .build();
                break;
            case GcpStorageImpl:
                Credentials gcpCredentials = null;
                try {
                    log.info("Credentials Length: {}", gcpStorageServiceProperties.getCredentials().length());
                    log.info("GCP Project: {}", gcpStorageServiceProperties.getProjectId());
                    log.info("GCP Bucket: {}", gcpStorageServiceProperties.getBucketName());

                    gcpCredentials = GoogleCredentials
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
                    storageService = GcpStorageServiceImpl.builder()
                            .bucketName(gcpStorageServiceProperties.getBucketName())
                            .storage(gcpStorage)
                            .gitService(new GitServiceImpl())
                            .registryHostname(openRegistryProperties.getHostname())
                            .build();
                } catch (IOException e) {
                    log.error(e.getMessage());
                }

                break;
            case Local:
                storageService = LocalStorageServiceImpl.builder()
                        .gitService(new GitServiceImpl())
                        .registryHostname(openRegistryProperties.getHostname())
                        .build();
                break;
            default:
                storageService = null;
        }
        return storageService;
    }

    private static @NotNull AwsBasicCredentials getAwsBasicCredentials(AwsStorageServiceProperties
                                                                               awsStorageServiceProperties) {
        return AwsBasicCredentials.create(awsStorageServiceProperties.getAccessKey(), awsStorageServiceProperties.getSecretKey());
    }
}
