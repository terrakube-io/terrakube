package io.terrakube.api.plugin.storage.configuration;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.aws.AwsStorageTypeProperties;
import io.terrakube.api.plugin.storage.aws.AwsStorageTypeServiceImpl;
import io.terrakube.api.plugin.storage.azure.AzureStorageTypeProperties;
import io.terrakube.api.plugin.storage.azure.AzureStorageTypeServiceImpl;
import io.terrakube.api.plugin.storage.gcp.GcpStorageTypeProperties;
import io.terrakube.api.plugin.storage.gcp.GcpStorageTypeServiceImpl;
import io.terrakube.api.plugin.storage.local.LocalStorageTypeServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.terrakube.api.plugin.streaming.StreamingService;
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
import java.util.concurrent.CompletableFuture;

@Configuration
@EnableConfigurationProperties({
        AzureStorageTypeProperties.class,
        StorageTypeProperties.class,
        GcpStorageTypeProperties.class
})
@ConditionalOnMissingBean(StorageTypeService.class)
@Slf4j
public class StorageTypeAutoConfiguration {

    @Bean
    public StorageTypeService terraformOutput(StreamingService streamingService, StorageTypeProperties storageTypeProperties, AzureStorageTypeProperties azureStorageTypeProperties, AwsStorageTypeProperties awsStorageTypeProperties, GcpStorageTypeProperties gcpStorageTypeProperties) {
        StorageTypeService storageTypeService = null;
        log.info("StorageType={}", storageTypeProperties.getType());
        switch (storageTypeProperties.getType()) {
            case AZURE:
                BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                        .connectionString(
                                String.format("DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                                        azureStorageTypeProperties.getAccountName(),
                                        azureStorageTypeProperties.getAccountKey())
                        ).buildClient();

                storageTypeService = AzureStorageTypeServiceImpl.builder()
                        .blobServiceClient(blobServiceClient)
                        .build();
                break;
            case AWS:
                S3Client s3client;
                if (awsStorageTypeProperties.getEndpoint() != null && !awsStorageTypeProperties.getEndpoint().isEmpty()) {
                    log.info("Creating AWS SDK with custom endpoint and custom credentials");
                    s3client = S3Client.builder()
                            .region(Region.of("auto"))
                            .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsStorageTypeProperties)))
                            .endpointProvider(new S3EndpointProvider() {
                                @Override
                                public CompletableFuture<Endpoint> resolveEndpoint(S3EndpointParams endpointParams) {
                                    return CompletableFuture.completedFuture(Endpoint.builder()
                                            .url(URI.create(awsStorageTypeProperties.getEndpoint() + "/" + endpointParams.bucket()))
                                            .build());
                                }
                            })
                            .forcePathStyle(true)
                            .build();

                } else {
                    if (awsStorageTypeProperties.isEnableRoleAuthentication()) {
                        log.info("Creating AWS SDK with default credentials");
                        s3client = S3Client.builder()
                                .region(Region.of(awsStorageTypeProperties.getRegion()))
                                .credentialsProvider(DefaultCredentialsProvider.create())
                                .build();
                    } else {
                        log.info("Creating AWS SDK with custom credentials");
                        s3client = S3Client.builder()
                                .region(Region.of(awsStorageTypeProperties.getRegion()))
                                .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsStorageTypeProperties)))
                                .build();
                    }
                }

                storageTypeService = AwsStorageTypeServiceImpl.builder()
                        .s3client(s3client)
                        .bucketName(awsStorageTypeProperties.getBucketName())
                        .build();
                break;
            case GCP:
                log.info("GCP Base64 {} length", gcpStorageTypeProperties.getCredentials().length());
                Credentials gcpCredentials = null;
                try {
                    gcpCredentials = GoogleCredentials.fromStream(
                            new ByteArrayInputStream(
                                    Base64.decodeBase64(gcpStorageTypeProperties.getCredentials())
                            )
                    );
                    Storage gcpStorage = StorageOptions.newBuilder()
                            .setCredentials(gcpCredentials)
                            .setProjectId(gcpStorageTypeProperties.getProjectId())
                            .build()
                            .getService();

                    storageTypeService = GcpStorageTypeServiceImpl.builder()
                            .storage(gcpStorage)
                            .bucketName(gcpStorageTypeProperties.getBucketName())
                            .build();
                } catch (IOException e) {
                    log.error(e.getMessage());
                }

                break;
            default:
                storageTypeService = LocalStorageTypeServiceImpl.builder().build();
        }
        return storageTypeService;
    }

    private AwsBasicCredentials getAwsBasicCredentials(AwsStorageTypeProperties awsStorageTypeProperties) {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(awsStorageTypeProperties.getAccessKey(), awsStorageTypeProperties.getSecretKey());
        return awsCreds;
    }
}
