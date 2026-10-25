package io.terrakube.executor.configuration.security;

import io.terrakube.executor.configuration.RedisProperties;
import io.terrakube.executor.plugin.tfstate.aws.AwsTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.azure.AzureTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.gcp.GcpTerraformStateProperties;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorSecretEnvironmentPostProcessorTest {

    @TempDir
    File tempDir;

    @Test
    void testPostProcessEnvironmentRegistersMapPropertySourceAndBindsConfigurationProperties() throws IOException {
        File secretsDir = new File(tempDir, "ephemeral-k8s");
        assertTrue(secretsDir.mkdirs());

        File redisPass = new File(secretsDir, "TerrakubeRedisPassword");
        FileUtils.writeStringToFile(redisPass, "redis-top-secret-999", StandardCharsets.UTF_8);

        File internalSecret = new File(secretsDir, "InternalSecret");
        FileUtils.writeStringToFile(internalSecret, "jwt-top-secret-888", StandardCharsets.UTF_8);

        File awsSecretKey = new File(secretsDir, "AwsTerraformStateSecretKey");
        FileUtils.writeStringToFile(awsSecretKey, "aws-top-secret-777", StandardCharsets.UTF_8);

        File awsAccessKey = new File(secretsDir, "AwsTerraformStateAccessKey");
        FileUtils.writeStringToFile(awsAccessKey, "aws-top-access-666", StandardCharsets.UTF_8);

        File azureStorageKey = new File(secretsDir, "AzureTerraformStateStorageAccessKey");
        FileUtils.writeStringToFile(azureStorageKey, "azure-top-secret-555", StandardCharsets.UTF_8);

        File gcpCreds = new File(secretsDir, "GcpTerraformStateCredentials");
        FileUtils.writeStringToFile(gcpCreds, "{\"client_email\": \"sa@test.iam.gserviceaccount.com\"}", StandardCharsets.UTF_8);

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("testConfig", Map.of(
                        "io.terrakube.executor.secrets.directory", secretsDir.getAbsolutePath()
                ))
        );

        ExecutorSecretEnvironmentPostProcessor processor = new ExecutorSecretEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication());

        // 1. Verify property source was registered
        assertTrue(environment.getPropertySources().contains(ExecutorSecretEnvironmentPostProcessor.PROPERTY_SOURCE_NAME));

        // 2. Verify raw environment lookups for canonical property names
        assertEquals("redis-top-secret-999", environment.getProperty("io.terrakube.executor.redis.password"));
        assertEquals("jwt-top-secret-888", environment.getProperty("io.terrakube.client.secretKey"));
        assertEquals("aws-top-secret-777", environment.getProperty("io.terrakube.executor.plugin.tfstate.aws.secretKey"));
        assertEquals("aws-top-access-666", environment.getProperty("io.terrakube.executor.plugin.tfstate.aws.accessKey"));
        assertEquals("azure-top-secret-555", environment.getProperty("io.terrakube.executor.plugin.tfstate.azure.storageAccessKey"));
        assertEquals("{\"client_email\": \"sa@test.iam.gserviceaccount.com\"}", environment.getProperty("io.terrakube.executor.plugin.tfstate.gcp.credentials"));

        // 3. Verify raw environment lookups for alias names
        assertEquals("redis-top-secret-999", environment.getProperty("TerrakubeRedisPassword"));
        assertEquals("jwt-top-secret-888", environment.getProperty("InternalSecret"));
        assertEquals("aws-top-secret-777", environment.getProperty("AwsTerraformStateSecretKey"));
        assertEquals("aws-top-access-666", environment.getProperty("AwsTerraformStateAccessKey"));
        assertEquals("azure-top-secret-555", environment.getProperty("AzureTerraformStateStorageAccessKey"));
        assertEquals("{\"client_email\": \"sa@test.iam.gserviceaccount.com\"}", environment.getProperty("GcpTerraformStateCredentials"));

        // 4. Verify Spring Boot @ConfigurationProperties binding works seamlessly
        RedisProperties redisProperties = Binder.get(environment)
                .bind("io.terrakube.executor.redis", RedisProperties.class)
                .get();
        assertEquals("redis-top-secret-999", redisProperties.getPassword());

        AwsTerraformStateProperties awsProperties = Binder.get(environment)
                .bind("io.terrakube.executor.plugin.tfstate.aws", AwsTerraformStateProperties.class)
                .get();
        assertEquals("aws-top-secret-777", awsProperties.getSecretKey());
        assertEquals("aws-top-access-666", awsProperties.getAccessKey());

        AzureTerraformStateProperties azureProperties = Binder.get(environment)
                .bind("io.terrakube.executor.plugin.tfstate.azure", AzureTerraformStateProperties.class)
                .get();
        assertEquals("azure-top-secret-555", azureProperties.getStorageAccessKey());

        GcpTerraformStateProperties gcpProperties = Binder.get(environment)
                .bind("io.terrakube.executor.plugin.tfstate.gcp", GcpTerraformStateProperties.class)
                .get();
        assertEquals("{\"client_email\": \"sa@test.iam.gserviceaccount.com\"}", gcpProperties.getCredentials());

        // 5. Verify all secret files were deleted
        assertFalse(redisPass.exists(), "Redis password file must be deleted");
        assertFalse(internalSecret.exists(), "Internal secret file must be deleted");
        assertFalse(awsSecretKey.exists(), "AWS secret key file must be deleted");
        assertFalse(awsAccessKey.exists(), "AWS access key file must be deleted");
        assertFalse(azureStorageKey.exists(), "Azure storage key file must be deleted");
        assertFalse(gcpCreds.exists(), "GCP credentials file must be deleted");
    }

    @Test
    void testChildProcessSpawnedViaProcessBuilderDoesNotContainEphemeralSecretsInEnv() throws IOException, InterruptedException {
        // Ephemeral secrets are stored in Spring Environment in-memory MapPropertySource.
        // They are NEVER set as OS environment variables in System.getenv().
        // Verify that a spawned process (like terraform) cannot see these secrets in its environment.
        Process process = new ProcessBuilder("sh", "-c", "echo \"REDIS=${TerrakubeRedisPassword} AWS=${AwsTerraformStateSecretKey} INT=${InternalSecret}\"")
                .start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }
        process.waitFor();

        assertEquals("REDIS= AWS= INT=", output, "Spawned child process environment must not contain sensitive executor secrets");
    }

    @Test
    void testPostProcessEnvironmentPropagatesIllegalStateExceptionWhenDirectoryMissing() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("testConfig", Map.of(
                        "io.terrakube.executor.secrets.directory", "/non/existent/dir"
                ))
        );

        ExecutorSecretEnvironmentPostProcessor processor = new ExecutorSecretEnvironmentPostProcessor();
        assertThrows(IllegalStateException.class, () -> processor.postProcessEnvironment(environment, new SpringApplication()));
    }

    @Test
    void testPostProcessEnvironmentWithRealApplicationPropertiesDoesNotThrowWhenSecretsDirectoryMissing() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new org.springframework.core.io.support.ResourcePropertySource("classpath:application.properties"));

        ExecutorSecretEnvironmentPostProcessor processor = new ExecutorSecretEnvironmentPostProcessor();
        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, new SpringApplication()));
    }

    @Test
    void testEnvironmentPostProcessorIsRegisteredInSpringFactories() {
        java.util.List<String> factoryNames =
                org.springframework.core.io.support.SpringFactoriesLoader.loadFactoryNames(
                        org.springframework.boot.EnvironmentPostProcessor.class,
                        getClass().getClassLoader()
                );
        assertTrue(factoryNames.contains(ExecutorSecretEnvironmentPostProcessor.class.getName()),
                "ExecutorSecretEnvironmentPostProcessor must be registered under org.springframework.boot.EnvironmentPostProcessor in META-INF/spring.factories");
    }
}
