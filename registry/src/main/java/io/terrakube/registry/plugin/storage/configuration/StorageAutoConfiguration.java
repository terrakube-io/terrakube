package io.terrakube.registry.plugin.storage.configuration;


import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;

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
                        .presignedUrlExpirySeconds(azureStorageServiceProperties.getPresignedUrlExpirySeconds())
                        .presignedRedirectEnabled(azureStorageServiceProperties.isPresignedRedirectEnabled())
                        .build();
                break;
            case AwsStorageImpl:
                // Bounded pool/timeouts so a slow or unreachable S3 endpoint fails fast instead of
                // queuing registry request threads - see the module-download resilience design.
                SdkHttpClient s3HttpClient = ApacheHttpClient.builder()
                        .connectionAcquisitionTimeout(Duration.ofSeconds(awsStorageServiceProperties.getConnectionAcquisitionTimeoutSeconds()))
                        .connectionTimeout(Duration.ofSeconds(awsStorageServiceProperties.getConnectTimeoutSeconds()))
                        .socketTimeout(Duration.ofSeconds(awsStorageServiceProperties.getSocketTimeoutSeconds()))
                        .maxConnections(awsStorageServiceProperties.getMaxConnections())
                        .build();

                ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(awsStorageServiceProperties.getApiCallAttemptTimeoutSeconds()))
                        .apiCallTimeout(Duration.ofSeconds(awsStorageServiceProperties.getApiCallTimeoutSeconds()))
                        .retryPolicy(RetryPolicy.forRetryMode(RetryMode.STANDARD))
                        .build();

                AwsCredentialsProvider credentialsProvider = awsStorageServiceProperties.isEnableRoleAuthentication()
                        ? DefaultCredentialsProvider.create()
                        : StaticCredentialsProvider.create(getAwsBasicCredentials(awsStorageServiceProperties));

                var s3ClientBuilder = S3Client.builder()
                        .httpClient(s3HttpClient)
                        .overrideConfiguration(overrideConfiguration)
                        .credentialsProvider(credentialsProvider);
                var s3PresignerBuilder = S3Presigner.builder()
                        .credentialsProvider(credentialsProvider);

                if (awsStorageServiceProperties.getEndpoint() != null && !awsStorageServiceProperties.getEndpoint().isEmpty()
                        && !awsStorageServiceProperties.isEnableRoleAuthentication()) {
                    log.info("Creating AWS SDK with custom endpoint and custom credentials");

                    S3Configuration serviceConfiguration = S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .chunkedEncodingEnabled(awsStorageServiceProperties.isChunkedEncodingEnabled())
                            .checksumValidationEnabled(awsStorageServiceProperties.isChecksumValidationEnabled())
                            .build();

                    s3ClientBuilder
                            .region(Region.of(awsStorageServiceProperties.getEndpointRegion()))
                            .endpointOverride(URI.create(awsStorageServiceProperties.getEndpoint()))
                            .serviceConfiguration(serviceConfiguration)
                            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
                    s3PresignerBuilder
                            .region(Region.of(awsStorageServiceProperties.getEndpointRegion()))
                            .endpointOverride(URI.create(awsStorageServiceProperties.getEndpoint()))
                            .serviceConfiguration(serviceConfiguration);
                } else {
                    log.info("Creating AWS SDK with {} credentials",
                            awsStorageServiceProperties.isEnableRoleAuthentication() ? "default" : "custom");
                    s3ClientBuilder.region(Region.of(awsStorageServiceProperties.getRegion()));
                    s3PresignerBuilder.region(Region.of(awsStorageServiceProperties.getRegion()));
                }

                storageService = AwsStorageServiceImpl.builder()
                        .s3client(s3ClientBuilder.build())
                        .s3Presigner(s3PresignerBuilder.build())
                        .gitService(new GitServiceImpl())
                        .bucketName(awsStorageServiceProperties.getBucketName())
                        .registryHostname(openRegistryProperties.getHostname())
                        .presignedUrlExpirySeconds(awsStorageServiceProperties.getPresignedUrlExpirySeconds())
                        .presignedRedirectEnabled(awsStorageServiceProperties.isPresignedRedirectEnabled())
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
                            .presignedUrlExpirySeconds(gcpStorageServiceProperties.getPresignedUrlExpirySeconds())
                            .presignedRedirectEnabled(gcpStorageServiceProperties.isPresignedRedirectEnabled())
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

    private static AwsBasicCredentials getAwsBasicCredentials(AwsStorageServiceProperties
                                                                               awsStorageServiceProperties) {
        return AwsBasicCredentials.create(awsStorageServiceProperties.getAccessKey(), awsStorageServiceProperties.getSecretKey());
    }
}
