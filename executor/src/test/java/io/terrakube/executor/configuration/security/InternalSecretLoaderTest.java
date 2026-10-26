package io.terrakube.executor.configuration.security;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InternalSecretLoaderTest {

    @TempDir
    File tempDir;

    @Test
    void testDirectSecretTakesPrecedence() throws IOException {
        File secretFile = new File(tempDir, "secret.txt");
        FileUtils.writeStringToFile(secretFile, "file-secret-value", StandardCharsets.UTF_8);

        InternalSecretLoader loader = new InternalSecretLoader("direct-secret-value", secretFile.getAbsolutePath());

        assertEquals("direct-secret-value", loader.getInternalSecret());
        assertTrue(secretFile.exists(), "Direct secret should not delete the file");
    }

    @Test
    void testLoadSecretFromFileAndShredAndDelete() throws IOException {
        File secretFile = new File(tempDir, "secret.txt");
        String secretContent = "super-secret-token-12345";
        FileUtils.writeStringToFile(secretFile, secretContent, StandardCharsets.UTF_8);

        assertTrue(secretFile.exists());

        InternalSecretLoader loader = new InternalSecretLoader(null, secretFile.getAbsolutePath());

        assertEquals(secretContent, loader.getInternalSecret());
        assertFalse(secretFile.exists(), "Secret file must be shredded and deleted after being read into memory");
    }

    @Test
    void testLoadSecretFromBlankDirectSecretFallsBackToFile() throws IOException {
        File secretFile = new File(tempDir, "secret.txt");
        String secretContent = "fallback-secret-value";
        FileUtils.writeStringToFile(secretFile, secretContent, StandardCharsets.UTF_8);

        InternalSecretLoader loader = new InternalSecretLoader("   ", secretFile.getAbsolutePath());

        assertEquals(secretContent, loader.getInternalSecret());
        assertFalse(secretFile.exists(), "File must be deleted when falling back from blank direct secret");
    }

    @Test
    void testNonExistentFileHandlesGracefully() {
        File nonExistent = new File(tempDir, "does-not-exist.txt");

        InternalSecretLoader loader = new InternalSecretLoader("", nonExistent.getAbsolutePath());

        assertEquals("", loader.getInternalSecret());
    }

    @Test
    void testNullAndEmptyInputsReturnEmptyString() {
        InternalSecretLoader loader = new InternalSecretLoader(null, null);
        assertEquals("", loader.getInternalSecret());

        InternalSecretLoader loader2 = new InternalSecretLoader("  ", "");
        assertEquals("", loader2.getInternalSecret());
    }

    @Test
    void testLoadSecretsFromDirectoryWithKeyFiles() throws IOException {
        File secretsDir = new File(tempDir, "k8s-secrets");
        assertTrue(secretsDir.mkdirs());

        File internalSecretFile = new File(secretsDir, "InternalSecret");
        FileUtils.writeStringToFile(internalSecretFile, "jwt-secret-abc", StandardCharsets.UTF_8);

        File redisPassFile = new File(secretsDir, "TerrakubeRedisPassword");
        FileUtils.writeStringToFile(redisPassFile, "redis-secret-pass", StandardCharsets.UTF_8);

        File awsSecretFile = new File(secretsDir, "AwsTerraformStateSecretKey");
        FileUtils.writeStringToFile(awsSecretFile, "aws-secret-key-123", StandardCharsets.UTF_8);

        File awsAccessFile = new File(secretsDir, "AwsTerraformStateAccessKey");
        FileUtils.writeStringToFile(awsAccessFile, "aws-access-key-123", StandardCharsets.UTF_8);

        File azureAccessFile = new File(secretsDir, "AzureTerraformStateStorageAccessKey");
        FileUtils.writeStringToFile(azureAccessFile, "azure-storage-key-456", StandardCharsets.UTF_8);

        File gcpCredsFile = new File(secretsDir, "GcpTerraformStateCredentials");
        FileUtils.writeStringToFile(gcpCredsFile, "{\"type\": \"service_account\"}", StandardCharsets.UTF_8);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("io.terrakube.executor.secrets.directory", secretsDir.getAbsolutePath());

        Map<String, Object> secrets = InternalSecretLoader.loadSecrets(env);

        // Verify canonical properties and alias properties are populated
        assertEquals("jwt-secret-abc", secrets.get("io.terrakube.client.secretKey"));
        assertEquals("jwt-secret-abc", secrets.get("InternalSecret"));

        assertEquals("redis-secret-pass", secrets.get("io.terrakube.executor.redis.password"));
        assertEquals("redis-secret-pass", secrets.get("TerrakubeRedisPassword"));

        assertEquals("aws-secret-key-123", secrets.get("io.terrakube.executor.plugin.tfstate.aws.secretKey"));
        assertEquals("aws-secret-key-123", secrets.get("AwsTerraformStateSecretKey"));

        assertEquals("aws-access-key-123", secrets.get("io.terrakube.executor.plugin.tfstate.aws.accessKey"));
        assertEquals("aws-access-key-123", secrets.get("AwsTerraformStateAccessKey"));

        assertEquals("azure-storage-key-456", secrets.get("io.terrakube.executor.plugin.tfstate.azure.storageAccessKey"));
        assertEquals("azure-storage-key-456", secrets.get("AzureTerraformStateStorageAccessKey"));

        assertEquals("{\"type\": \"service_account\"}", secrets.get("io.terrakube.executor.plugin.tfstate.gcp.credentials"));
        assertEquals("{\"type\": \"service_account\"}", secrets.get("GcpTerraformStateCredentials"));

        // Verify files were shredded and deleted
        assertFalse(internalSecretFile.exists(), "InternalSecret file must be shredded and deleted");
        assertFalse(redisPassFile.exists(), "Redis password file must be shredded and deleted");
        assertFalse(awsSecretFile.exists(), "AWS secret key file must be shredded and deleted");
        assertFalse(awsAccessFile.exists(), "AWS access key file must be shredded and deleted");
        assertFalse(azureAccessFile.exists(), "Azure access key file must be shredded and deleted");
        assertFalse(gcpCredsFile.exists(), "GCP credentials file must be shredded and deleted");
    }

    @Test
    void testLoadSecretsFromDirectoryWithEnvFile() throws IOException {
        File secretsDir = new File(tempDir, "env-secrets-dir");
        assertTrue(secretsDir.mkdirs());

        File envFile = new File(secretsDir, "executor.env");
        String envContent = """
                # Devcontainer secrets
                TerrakubeRedisPassword=dev-redis-pass
                InternalSecret="dev-internal-secret"
                AwsTerraformStateSecretKey='dev-aws-secret'
                """;
        FileUtils.writeStringToFile(envFile, envContent, StandardCharsets.UTF_8);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("io.terrakube.executor.secrets.directory", secretsDir.getAbsolutePath());

        Map<String, Object> secrets = InternalSecretLoader.loadSecrets(env);

        assertEquals("dev-redis-pass", secrets.get("io.terrakube.executor.redis.password"));
        assertEquals("dev-redis-pass", secrets.get("TerrakubeRedisPassword"));
        assertEquals("dev-internal-secret", secrets.get("io.terrakube.client.secretKey"));
        assertEquals("dev-internal-secret", secrets.get("InternalSecret"));
        assertEquals("dev-aws-secret", secrets.get("io.terrakube.executor.plugin.tfstate.aws.secretKey"));

        assertFalse(envFile.exists(), "Env file must be shredded and deleted");
    }

    @Test
    void testLoadSecretsFromCombinedFile() throws IOException {
        File combinedFile = new File(tempDir, "combined.env");
        String content = "TerrakubeRedisPassword=combined-pass\nInternalSecret=combined-internal";
        FileUtils.writeStringToFile(combinedFile, content, StandardCharsets.UTF_8);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("io.terrakube.executor.secrets.file", combinedFile.getAbsolutePath());

        Map<String, Object> secrets = InternalSecretLoader.loadSecrets(env);

        assertEquals("combined-pass", secrets.get("io.terrakube.executor.redis.password"));
        assertEquals("combined-internal", secrets.get("io.terrakube.client.secretKey"));

        assertFalse(combinedFile.exists(), "Combined secret file must be shredded and deleted");
    }

    @Test
    void testLoadSecretsFromIndividualFileProperties() throws IOException {
        File redisPassFile = new File(tempDir, "redis.pass");
        FileUtils.writeStringToFile(redisPassFile, "indiv-redis-pass", StandardCharsets.UTF_8);

        File awsSecretFile = new File(tempDir, "aws.secret");
        FileUtils.writeStringToFile(awsSecretFile, "indiv-aws-secret", StandardCharsets.UTF_8);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("io.terrakube.executor.redis.passwordFile", redisPassFile.getAbsolutePath());
        env.setProperty("AwsTerraformStateSecretKeyFile", awsSecretFile.getAbsolutePath());

        Map<String, Object> secrets = InternalSecretLoader.loadSecrets(env);

        assertEquals("indiv-redis-pass", secrets.get("io.terrakube.executor.redis.password"));
        assertEquals("indiv-aws-secret", secrets.get("io.terrakube.executor.plugin.tfstate.aws.secretKey"));

        assertFalse(redisPassFile.exists(), "Individual redis pass file must be deleted");
        assertFalse(awsSecretFile.exists(), "Individual aws secret file must be deleted");
    }

    @Test
    void testRegisterSecretIgnoresUnrecognizedKeys() {
        Map<String, Object> map = new HashMap<>();
        InternalSecretLoader.registerSecret(map, "server.port", "8080");
        InternalSecretLoader.registerSecret(map, "io.terrakube.some.random.prop", "val");
        assertTrue(map.isEmpty(), "Unrecognized property keys must be ignored");

        InternalSecretLoader.registerSecret(map, "InternalSecret", "my-secret");
        assertEquals("my-secret", map.get("InternalSecret"));
        assertEquals("my-secret", map.get("io.terrakube.client.secretKey"));
    }

    @Test
    void testExplicitMissingDirectoryThrowsException() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("io.terrakube.executor.secrets.directory", "/non/existent/path/12345");

        assertThrows(IllegalStateException.class, () -> InternalSecretLoader.loadSecrets(env));
    }

    @Test
    void testExplicitMissingCombinedFileThrowsException() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("io.terrakube.executor.secrets.file", "/non/existent/file.env");

        assertThrows(IllegalStateException.class, () -> InternalSecretLoader.loadSecrets(env));
    }

    @Test
    void testDefaultDirectoryMissingDoesNotThrow() {
        MockEnvironment env = new MockEnvironment();
        // Neither io.terrakube.executor.secrets.directory nor ExecutorSecretsDirectory is configured
        assertDoesNotThrow(() -> {
            Map<String, Object> secrets = InternalSecretLoader.loadSecrets(env);
            assertNotNull(secrets);
        });
    }

    @Test
    void testOutputStorageSecretsRecognized() {
        Map<String, Object> map = new HashMap<>();
        InternalSecretLoader.registerSecret(map, "AwsTerraformOutputAccessKey", "output-access");
        InternalSecretLoader.registerSecret(map, "AwsTerraformOutputSecretKey", "output-secret");
        InternalSecretLoader.registerSecret(map, "AzureTerraformOutputAccountKey", "azure-output-key");
        InternalSecretLoader.registerSecret(map, "GcpTerraformOutputCredentials", "gcp-output-creds");

        assertEquals("output-access", map.get("AwsTerraformOutputAccessKey"));
        assertEquals("output-secret", map.get("AwsTerraformOutputSecretKey"));
        assertEquals("azure-output-key", map.get("AzureTerraformOutputAccountKey"));
        assertEquals("gcp-output-creds", map.get("GcpTerraformOutputCredentials"));
    }

    @Test
    void testUnreadableSecretFileThrowsIllegalStateException() throws IOException {
        File secretsDir = new File(tempDir, "unreadable-secrets-dir");
        assertTrue(secretsDir.mkdirs());

        File secretFile = new File(secretsDir, "InternalSecret");
        FileUtils.writeStringToFile(secretFile, "secret-data", StandardCharsets.UTF_8);

        // Make file unreadable
        if (secretFile.setReadable(false)) {
            try {
                MockEnvironment env = new MockEnvironment();
                env.setProperty("io.terrakube.executor.secrets.directory", secretsDir.getAbsolutePath());

                assertThrows(IllegalStateException.class, () -> InternalSecretLoader.loadSecrets(env));
            } finally {
                secretFile.setReadable(true);
            }
        }
    }
}
