package io.terrakube.storage.plugin.aws;

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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Slf4j
@AutoConfiguration
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(prefix = "io.terrakube.registry.plugin.storage", name = "type", havingValue = "AwsStorageImpl")
@EnableConfigurationProperties(AwsStorageServiceProperties.class)
public class AwsStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService awsStorageService(AwsStorageServiceProperties awsStorageServiceProperties,
                                            GitService gitService,
                                            @Value("${io.terrakube.registry.hostname:${org.openregistry.hostname:http://localhost:8080}}") String registryHostname) {
        S3Client s3client;
        if (awsStorageServiceProperties.isEnableRoleAuthentication()) {
            log.info("Creating AWS SDK with default credentials");
            s3client = S3Client.builder()
                    .region(Region.of(awsStorageServiceProperties.getRegion()))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
        } else if (awsStorageServiceProperties.getEndpoint() != null && !awsStorageServiceProperties.getEndpoint().isEmpty()) {
            log.info("Creating AWS SDK with custom endpoint and custom credentials");

            S3Configuration serviceConfiguration = S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build();

            s3client = S3Client.builder()
                    .region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsStorageServiceProperties)))
                    .endpointOverride(URI.create(awsStorageServiceProperties.getEndpoint()))
                    .serviceConfiguration(serviceConfiguration)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .build();

        } else {
            log.info("Creating AWS SDK with custom credentials");
            s3client = S3Client.builder()
                    .region(Region.of(awsStorageServiceProperties.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsStorageServiceProperties)))
                    .build();
        }

        return AwsStorageServiceImpl.builder()
                .s3client(s3client)
                .gitService(gitService)
                .bucketName(awsStorageServiceProperties.getBucketName())
                .registryHostname(registryHostname)
                .build();
    }

    private static AwsBasicCredentials getAwsBasicCredentials(AwsStorageServiceProperties awsStorageServiceProperties) {
        return AwsBasicCredentials.create(awsStorageServiceProperties.getAccessKey(), awsStorageServiceProperties.getSecretKey());
    }
}
