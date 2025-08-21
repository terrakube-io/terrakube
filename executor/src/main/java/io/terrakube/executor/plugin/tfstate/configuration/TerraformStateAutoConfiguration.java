package io.terrakube.executor.plugin.tfstate.configuration;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.orbitz.consul.Consul;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.plugin.tfstate.aws.AwsTerraformStateImpl;
import io.terrakube.executor.plugin.tfstate.aws.AwsTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.azure.AzureTerraformStateImpl;
import io.terrakube.executor.plugin.tfstate.azure.AzureTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.consul.ConsulTerraformStateImpl;
import io.terrakube.executor.plugin.tfstate.consul.ConsulTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.gcp.GcpTerraformStateImpl;
import io.terrakube.executor.plugin.tfstate.gcp.GcpTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.local.LocalTerraformStateImpl;
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
import java.util.concurrent.CompletableFuture;

@Slf4j
@AllArgsConstructor
@Configuration
@EnableConfigurationProperties({
        TerraformStateProperties.class,
        AzureTerraformStateProperties.class,
        AwsTerraformStateProperties.class,
        GcpTerraformStateProperties.class,
        ConsulTerraformStateProperties.class
})
@ConditionalOnMissingBean(TerraformState.class)
public class TerraformStateAutoConfiguration {
    
    @Bean
    public TerraformState terraformState(
            TerrakubeClient terrakubeClient, 
            TerraformStateProperties terraformStateProperties, 
            AzureTerraformStateProperties azureTerraformStateProperties, 
            AwsTerraformStateProperties awsTerraformStateProperties, 
            GcpTerraformStateProperties gcpTerraformStateProperties,
            ConsulTerraformStateProperties consulTerraformStateProperties,
            TerraformStatePathService terraformStatePathService, 
            TerraformOutputPathService terraformOutputPathService) {
        
        TerraformState terraformState = null;
        
        if (terraformStateProperties != null) {
            switch (terraformStateProperties.getType()) {
                case AzureTerraformStateImpl:
                    BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                            .connectionString(
                                    String.format("DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                                            azureTerraformStateProperties.getStorageAccountName(),
                                            azureTerraformStateProperties.getStorageAccessKey())
                            ).buildClient();
                    terraformState = AzureTerraformStateImpl.builder()
                            .resourceGroupName(azureTerraformStateProperties.getResourceGroupName())
                            .storageAccountName(azureTerraformStateProperties.getStorageAccountName())
                            .storageContainerName(azureTerraformStateProperties.getStorageContainerName())
                            .storageAccessKey(azureTerraformStateProperties.getStorageAccessKey())
                            .blobServiceClient(blobServiceClient)
                            .terrakubeClient(terrakubeClient)
                            .terraformOutputPathService(terraformOutputPathService)
                            .terraformStatePathService(terraformStatePathService)
                            .build();
                    break;
                    
                case AwsTerraformStateImpl:
                    S3Client s3client = null;
                    if (awsTerraformStateProperties.getEndpoint() != null && !awsTerraformStateProperties.getEndpoint().isEmpty()) {
                        log.info("Creating AWS with custom endpoint and custom credentials");
                        s3client = S3Client.builder()
                                .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsTerraformStateProperties)))
                                .region(Region.AWS_GLOBAL)
                                .endpointProvider(new S3EndpointProvider() {
                                    @Override
                                    public CompletableFuture<Endpoint> resolveEndpoint(S3EndpointParams endpointParams) {
                                        return CompletableFuture.completedFuture(Endpoint.builder()
                                                .url(URI.create(awsTerraformStateProperties.getEndpoint() + "/" + endpointParams.bucket()))
                                                .build());
                                    }
                                })
                                .build();
                    } else {
                        if (awsTerraformStateProperties.isEnableRoleAuthentication()){
                            log.info("Creating AWS SDK with default credentials");
                            s3client = S3Client.builder()
                                    .credentialsProvider(DefaultCredentialsProvider.create())
                                    .region(Region.of(awsTerraformStateProperties.getRegion()))
                                    .build();
                        } else {
                            log.info("Creating AWS SDK with custom credentials");
                            s3client = S3Client.builder()
                                    .region(Region.of(awsTerraformStateProperties.getRegion()))
                                    .credentialsProvider(StaticCredentialsProvider.create(getAwsBasicCredentials(awsTerraformStateProperties)))
                                    .build();
                        }
                    }

                    terraformState = AwsTerraformStateImpl.builder()
                            .s3client(s3client)
                            .endpoint(!awsTerraformStateProperties.getEndpoint().equals("") ? awsTerraformStateProperties.getEndpoint(): null)
                            .bucketName(awsTerraformStateProperties.getBucketName())
                            .accessKey(awsTerraformStateProperties.getAccessKey())
                            .secretKey(awsTerraformStateProperties.getSecretKey())
                            .region(Region.of(awsTerraformStateProperties.getRegion()))
                            .includeBackendKeys(awsTerraformStateProperties.isIncludeBackendKeys())
                            .terrakubeClient(terrakubeClient)
                            .terraformStatePathService(terraformStatePathService)
                            .terraformOutputPathService(terraformOutputPathService)
                            .build();
                    break;
                    
                case GcpTerraformStateImpl:
                    try {
                        log.info("GCP Credentials Base64 {} length", gcpTerraformStateProperties.getCredentials().length());
                        Credentials gcpCredentials = GoogleCredentials.fromStream(
                                new ByteArrayInputStream(
                                        Base64.decodeBase64(gcpTerraformStateProperties.getCredentials())
                                )
                        );
                        Storage gcpStorage = StorageOptions.newBuilder()
                                .setCredentials(gcpCredentials)
                                .setProjectId(gcpTerraformStateProperties.getProjectId())
                                .build()
                                .getService();
                        terraformState = GcpTerraformStateImpl.builder().storage(gcpStorage)
                                .terraformStatePathService(terraformStatePathService)
                                .terraformOutputPathService(terraformOutputPathService)
                                .bucketName(gcpTerraformStateProperties.getBucketName())
                                .credentials(gcpTerraformStateProperties.getCredentials())
                                .terrakubeClient(terrakubeClient)
                                .build();
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                    break;
                    
                case ConsulTerraformStateImpl:
                    log.info("Initializing Consul backend");
                    
                    // Initialize Consul client
                    log.info("Initializing Consul client for address: {}", consulTerraformStateProperties.getAddress());
                    
                    Consul.Builder consulBuilder = Consul.builder()
                            .withUrl(consulTerraformStateProperties.getScheme() + "://" + 
                                    consulTerraformStateProperties.getAddress());
                    
                    // Add token if provided
                    if (consulTerraformStateProperties.getToken() != null && 
                        !consulTerraformStateProperties.getToken().isEmpty()) {
                        consulBuilder.withAclToken(consulTerraformStateProperties.getToken());
                        log.info("Using provided Consul token");
                    } else {
                        log.info("No Consul token provided");
                    }
                    
                    Consul consulClient;
                    try {
                        consulClient = consulBuilder.build();
                        log.info("Consul client initialized successfully");
                    } catch (Exception e) {
                        log.error("Failed to initialize Consul client: {}", e.getMessage());
                        throw new RuntimeException("Failed to initialize Consul client", e);
                    }
                    
                    terraformState = ConsulTerraformStateImpl.builder()
                            .consulClient(consulClient)
                            .address(consulTerraformStateProperties.getAddress())
                            .scheme(consulTerraformStateProperties.getScheme())
                            .path(consulTerraformStateProperties.getPath())
                            .token(consulTerraformStateProperties.getToken())
                            .datacenter(consulTerraformStateProperties.getDatacenter())
                            .gzip(consulTerraformStateProperties.isGzip())
                            .lock(consulTerraformStateProperties.isLock())
                            .vaultAddress(consulTerraformStateProperties.getVaultAddress())
                            .vaultToken(consulTerraformStateProperties.getVaultToken())
                            .vaultTokenPath(consulTerraformStateProperties.getVaultTokenPath())
                            .useVaultForToken(consulTerraformStateProperties.isUseVaultForToken())
                            .terrakubeClient(terrakubeClient)
                            .terraformStatePathService(terraformStatePathService)
                            .terraformOutputPathService(terraformOutputPathService)
                            .build();
                    
                    log.info("Consul Terraform state backend initialized successfully");
                    break;
                    
                default:
                    log.info("Using default Local Terraform state backend");
                    terraformState = LocalTerraformStateImpl.builder()
                            .terrakubeClient(terrakubeClient)
                            .terraformStatePathService(terraformStatePathService)
                            .terraformOutputPathService(terraformOutputPathService)
                            .build();
            }
        } else {
            log.info("No terraform state properties found, using Local backend");
            terraformState = LocalTerraformStateImpl.builder()
                    .terrakubeClient(terrakubeClient)
                    .terraformStatePathService(terraformStatePathService)
                    .terraformOutputPathService(terraformOutputPathService)
                    .build();
        }
        
        return terraformState;
    }
    
    private AwsBasicCredentials getAwsBasicCredentials(AwsTerraformStateProperties awsTerraformStateProperties) {
        return AwsBasicCredentials.create(awsTerraformStateProperties.getAccessKey(), awsTerraformStateProperties.getSecretKey());
    }
}
