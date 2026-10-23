package io.terrakube.executor.configuration.security;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InternalSecretLoader {

    public static final String DEFAULT_SECRETS_DIRECTORY = "/etc/terrakube/secrets";
    public static final String LEGACY_INTERNAL_SECRET_DIRECTORY = "/etc/terrakube/internal-secret";

    public static final Map<String, String> SECRET_KEY_TO_CANONICAL_PROPERTY = Map.of(
            "InternalSecret", "io.terrakube.client.secretKey",
            "AwsTerraformStateAccessKey", "io.terrakube.executor.plugin.tfstate.aws.accessKey",
            "AwsTerraformStateSecretKey", "io.terrakube.executor.plugin.tfstate.aws.secretKey",
            "AzureTerraformStateStorageAccessKey", "io.terrakube.executor.plugin.tfstate.azure.storageAccessKey",
            "GcpTerraformStateCredentials", "io.terrakube.executor.plugin.tfstate.gcp.credentials",
            "TerrakubeRedisPassword", "io.terrakube.executor.redis.password",
            "TerrakubeRedisTruststorePassword", "io.terrakube.executor.redis.truststorePassword",
            "TerrakubeRedisSentinelPassword", "io.terrakube.executor.redis.sentinel.password"
    );

    public static final Map<String, String> CANONICAL_PROPERTY_TO_SECRET_KEY = Map.of(
            "io.terrakube.client.secretKey", "InternalSecret",
            "io.terrakube.executor.plugin.tfstate.aws.accessKey", "AwsTerraformStateAccessKey",
            "io.terrakube.executor.plugin.tfstate.aws.secretKey", "AwsTerraformStateSecretKey",
            "io.terrakube.executor.plugin.tfstate.azure.storageAccessKey", "AzureTerraformStateStorageAccessKey",
            "io.terrakube.executor.plugin.tfstate.gcp.credentials", "GcpTerraformStateCredentials",
            "io.terrakube.executor.redis.password", "TerrakubeRedisPassword",
            "io.terrakube.executor.redis.truststorePassword", "TerrakubeRedisTruststorePassword",
            "io.terrakube.executor.redis.sentinel.password", "TerrakubeRedisSentinelPassword"
    );

    public static final Map<String, String> FILE_PROPERTY_TO_CANONICAL_PROPERTY = Map.ofEntries(
            Map.entry("io.terrakube.client.secretKeyFile", "io.terrakube.client.secretKey"),
            Map.entry("InternalSecretFile", "io.terrakube.client.secretKey"),
            Map.entry("io.terrakube.executor.plugin.tfstate.aws.accessKeyFile", "io.terrakube.executor.plugin.tfstate.aws.accessKey"),
            Map.entry("AwsTerraformStateAccessKeyFile", "io.terrakube.executor.plugin.tfstate.aws.accessKey"),
            Map.entry("io.terrakube.executor.plugin.tfstate.aws.secretKeyFile", "io.terrakube.executor.plugin.tfstate.aws.secretKey"),
            Map.entry("AwsTerraformStateSecretKeyFile", "io.terrakube.executor.plugin.tfstate.aws.secretKey"),
            Map.entry("io.terrakube.executor.plugin.tfstate.azure.storageAccessKeyFile", "io.terrakube.executor.plugin.tfstate.azure.storageAccessKey"),
            Map.entry("AzureTerraformStateStorageAccessKeyFile", "io.terrakube.executor.plugin.tfstate.azure.storageAccessKey"),
            Map.entry("io.terrakube.executor.plugin.tfstate.gcp.credentialsFile", "io.terrakube.executor.plugin.tfstate.gcp.credentials"),
            Map.entry("GcpTerraformStateCredentialsFile", "io.terrakube.executor.plugin.tfstate.gcp.credentials"),
            Map.entry("io.terrakube.executor.redis.passwordFile", "io.terrakube.executor.redis.password"),
            Map.entry("TerrakubeRedisPasswordFile", "io.terrakube.executor.redis.password"),
            Map.entry("io.terrakube.executor.redis.truststorePasswordFile", "io.terrakube.executor.redis.truststorePassword"),
            Map.entry("TerrakubeRedisTruststorePasswordFile", "io.terrakube.executor.redis.truststorePassword"),
            Map.entry("io.terrakube.executor.redis.sentinel.passwordFile", "io.terrakube.executor.redis.sentinel.password"),
            Map.entry("TerrakubeRedisSentinelPasswordFile", "io.terrakube.executor.redis.sentinel.password")
    );

    @Getter
    private final String internalSecret;

    public InternalSecretLoader(
            @Value("${io.terrakube.client.secretKey:}") String internalJwtSecret,
            @Value("${io.terrakube.client.secretKeyFile:}") String internalSecretFile) {
        this.internalSecret = resolveAndPurgeSecret(internalJwtSecret, internalSecretFile);
    }

    private String resolveAndPurgeSecret(String directSecret, String secretFilePath) {
        if (directSecret != null && !directSecret.isBlank()) {
            return directSecret;
        }

        if (secretFilePath == null || secretFilePath.isBlank()) {
            log.warn("Neither internal secret nor secret file path was provided.");
            return "";
        }

        File file = new File(secretFilePath);
        if (!file.exists()) {
            log.warn("Internal secret file specified but does not exist: {}", secretFilePath);
            return "";
        }

        try {
            String secret = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
            shredAndDeleteFile(file);
            return secret;
        } catch (Exception e) {
            log.error("Failed to read internal secret file {}: {}", secretFilePath, e.getMessage());
            return "";
        }
    }

    /**
     * Ingests all ephemeral secret files/directories configured in the environment,
     * populating both canonical Spring property keys and legacy environment variable aliases,
     * and shredding and deleting every secret file immediately from disk.
     */
    public static Map<String, Object> loadSecrets(PropertyResolver resolver) {
        Map<String, Object> secrets = new HashMap<>();

        // 1. Secret Directory (Kubernetes 1-file-per-secret mounts and/or .env files)
        String configuredDir = resolver.getProperty("io.terrakube.executor.secrets.directory");
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = resolver.getProperty("ExecutorSecretsDirectory");
        }
        String directoryPath = (configuredDir != null && !configuredDir.isBlank()) ? configuredDir : DEFAULT_SECRETS_DIRECTORY;
        File secretsDir = new File(directoryPath);
        if (secretsDir.exists() && secretsDir.isDirectory()) {
            loadSecretsFromDirectory(secretsDir, secrets);
        } else if (configuredDir != null && !configuredDir.isBlank()) {
            log.warn("Configured ephemeral secrets directory does not exist: {}", configuredDir);
        }

        // 2. Legacy internal secret directory fallback (/etc/terrakube/internal-secret)
        File legacyDir = new File(LEGACY_INTERNAL_SECRET_DIRECTORY);
        if (legacyDir.exists() && legacyDir.isDirectory() && !legacyDir.equals(secretsDir)) {
            loadSecretsFromDirectory(legacyDir, secrets);
        }

        // 3. Combined Secrets File (useful for local devcontainers, .env format)
        String combinedFilePath = resolver.getProperty("io.terrakube.executor.secrets.file");
        if (combinedFilePath == null || combinedFilePath.isBlank()) {
            combinedFilePath = resolver.getProperty("ExecutorSecretsFile");
        }
        if (combinedFilePath != null && !combinedFilePath.isBlank()) {
            File combinedFile = new File(combinedFilePath);
            if (combinedFile.exists() && combinedFile.isFile()) {
                loadEnvFile(combinedFile, secrets);
            } else {
                log.warn("Configured ephemeral secrets file does not exist: {}", combinedFilePath);
            }
        }

        // 4. Individual per-property secret files
        for (Map.Entry<String, String> entry : FILE_PROPERTY_TO_CANONICAL_PROPERTY.entrySet()) {
            String fileProperty = entry.getKey();
            String canonicalProperty = entry.getValue();
            String filePath = resolver.getProperty(fileProperty);
            if (filePath != null && !filePath.isBlank()) {
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    try {
                        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                        registerSecret(secrets, canonicalProperty, content);
                        shredAndDeleteFile(file);
                    } catch (Exception e) {
                        log.error("Failed to read secret file for property {} ({}): {}", fileProperty, filePath, e.getMessage());
                    }
                }
            }
        }

        return secrets;
    }

    public static void loadSecretsFromDirectory(File directory, Map<String, Object> secrets) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile() || file.isHidden() || file.getName().startsWith(".")) {
                continue;
            }
            if (file.getName().endsWith(".env") || file.getName().endsWith(".properties")) {
                loadEnvFile(file, secrets);
            } else {
                try {
                    String content = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                    registerSecret(secrets, file.getName(), content);
                    shredAndDeleteFile(file);
                } catch (Exception e) {
                    log.error("Failed to read secret file {}: {}", file.getAbsolutePath(), e.getMessage());
                }
            }
        }
    }

    public static void loadEnvFile(File file, Map<String, Object> secrets) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eqIndex = trimmed.indexOf('=');
                if (eqIndex > 0) {
                    String key = trimmed.substring(0, eqIndex).trim();
                    String value = trimmed.substring(eqIndex + 1).trim();
                    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                        if (value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        }
                    }
                    registerSecret(secrets, key, value);
                }
            }
            shredAndDeleteFile(file);
        } catch (Exception e) {
            log.error("Failed to parse secret env file {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    public static void registerSecret(Map<String, Object> map, String rawKey, String value) {
        if (rawKey == null || rawKey.isBlank() || value == null) {
            return;
        }
        String trimmedKey = rawKey.trim();
        String trimmedVal = value.trim();
        map.put(trimmedKey, trimmedVal);

        if (SECRET_KEY_TO_CANONICAL_PROPERTY.containsKey(trimmedKey)) {
            map.put(SECRET_KEY_TO_CANONICAL_PROPERTY.get(trimmedKey), trimmedVal);
        } else if (CANONICAL_PROPERTY_TO_SECRET_KEY.containsKey(trimmedKey)) {
            map.put(CANONICAL_PROPERTY_TO_SECRET_KEY.get(trimmedKey), trimmedVal);
        }
    }

    /**
     * Overwrites file contents in-place across its entire length with zeros,
     * flushes to underlying storage via getFD().sync(), truncates to 0, and deletes the file.
     */
    public static void shredAndDeleteFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            long length = file.length();
            if (length > 0 && file.canWrite()) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
                    byte[] buffer = new byte[4096];
                    Arrays.fill(buffer, (byte) 0);
                    long remaining = length;
                    raf.seek(0);
                    while (remaining > 0) {
                        int toWrite = (int) Math.min(remaining, buffer.length);
                        raf.write(buffer, 0, toWrite);
                        remaining -= toWrite;
                    }
                    raf.getFD().sync();
                    raf.setLength(0);
                }
            }
            if (!file.delete()) {
                log.warn("Unable to delete secret file {}. Ensure the parent directory is writable (e.g. emptyDir).", file.getAbsolutePath());
            } else {
                log.info("Successfully shredded and deleted secret file: {}", file.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("Failed to overwrite and delete secret file {}: {}. Ensure the volume is writable.", file.getAbsolutePath(), e.getMessage());
            try {
                Files.deleteIfExists(file.toPath());
            } catch (Exception ignored) {
            }
        }
    }
}
