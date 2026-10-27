package io.terrakube.executor.configuration.security;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class InternalSecretLoader {

    public static final String DEFAULT_SECRETS_DIRECTORY = "/etc/terrakube/secrets";
    public static final String LEGACY_INTERNAL_SECRET_DIRECTORY = "/etc/terrakube/internal-secret";

    public static final String PROP_INTERNAL_SECRET = "io.terrakube.client.secretKey";
    public static final String PROP_AWS_ACCESS_KEY = "io.terrakube.executor.plugin.tfstate.aws.accessKey";
    public static final String PROP_AWS_SECRET_KEY = "io.terrakube.executor.plugin.tfstate.aws.secretKey";
    public static final String PROP_AZURE_STORAGE_ACCESS_KEY = "io.terrakube.executor.plugin.tfstate.azure.storageAccessKey";
    public static final String PROP_GCP_CREDENTIALS = "io.terrakube.executor.plugin.tfstate.gcp.credentials";
    public static final String PROP_REDIS_PASSWORD = "io.terrakube.executor.redis.password";
    public static final String PROP_REDIS_TRUSTSTORE_PASSWORD = "io.terrakube.executor.redis.truststorePassword";
    public static final String PROP_REDIS_SENTINEL_PASSWORD = "io.terrakube.executor.redis.sentinel.password";

    public static final Set<String> KNOWN_OUTPUT_SECRET_KEYS = Set.of(
            "AwsTerraformOutputAccessKey",
            "AwsTerraformOutputSecretKey",
            "AzureTerraformOutputAccountKey",
            "GcpTerraformOutputCredentials"
    );

    public static final Map<String, String> SECRET_KEY_TO_CANONICAL_PROPERTY = Map.of(
            "InternalSecret", PROP_INTERNAL_SECRET,
            "AwsTerraformStateAccessKey", PROP_AWS_ACCESS_KEY,
            "AwsTerraformStateSecretKey", PROP_AWS_SECRET_KEY,
            "AzureTerraformStateStorageAccessKey", PROP_AZURE_STORAGE_ACCESS_KEY,
            "GcpTerraformStateCredentials", PROP_GCP_CREDENTIALS,
            "TerrakubeRedisPassword", PROP_REDIS_PASSWORD,
            "TerrakubeRedisTruststorePassword", PROP_REDIS_TRUSTSTORE_PASSWORD,
            "TerrakubeRedisSentinelPassword", PROP_REDIS_SENTINEL_PASSWORD
    );

    public static final Map<String, String> CANONICAL_PROPERTY_TO_SECRET_KEY = Map.of(
            PROP_INTERNAL_SECRET, "InternalSecret",
            PROP_AWS_ACCESS_KEY, "AwsTerraformStateAccessKey",
            PROP_AWS_SECRET_KEY, "AwsTerraformStateSecretKey",
            PROP_AZURE_STORAGE_ACCESS_KEY, "AzureTerraformStateStorageAccessKey",
            PROP_GCP_CREDENTIALS, "GcpTerraformStateCredentials",
            PROP_REDIS_PASSWORD, "TerrakubeRedisPassword",
            PROP_REDIS_TRUSTSTORE_PASSWORD, "TerrakubeRedisTruststorePassword",
            PROP_REDIS_SENTINEL_PASSWORD, "TerrakubeRedisSentinelPassword"
    );

    public static final Map<String, String> FILE_PROPERTY_TO_CANONICAL_PROPERTY = Map.ofEntries(
            Map.entry("io.terrakube.client.secretKeyFile", PROP_INTERNAL_SECRET),
            Map.entry("InternalSecretFile", PROP_INTERNAL_SECRET),
            Map.entry("io.terrakube.executor.plugin.tfstate.aws.accessKeyFile", PROP_AWS_ACCESS_KEY),
            Map.entry("AwsTerraformStateAccessKeyFile", PROP_AWS_ACCESS_KEY),
            Map.entry("io.terrakube.executor.plugin.tfstate.aws.secretKeyFile", PROP_AWS_SECRET_KEY),
            Map.entry("AwsTerraformStateSecretKeyFile", PROP_AWS_SECRET_KEY),
            Map.entry("io.terrakube.executor.plugin.tfstate.azure.storageAccessKeyFile", PROP_AZURE_STORAGE_ACCESS_KEY),
            Map.entry("AzureTerraformStateStorageAccessKeyFile", PROP_AZURE_STORAGE_ACCESS_KEY),
            Map.entry("io.terrakube.executor.plugin.tfstate.gcp.credentialsFile", PROP_GCP_CREDENTIALS),
            Map.entry("GcpTerraformStateCredentialsFile", PROP_GCP_CREDENTIALS),
            Map.entry("io.terrakube.executor.redis.passwordFile", PROP_REDIS_PASSWORD),
            Map.entry("TerrakubeRedisPasswordFile", PROP_REDIS_PASSWORD),
            Map.entry("io.terrakube.executor.redis.truststorePasswordFile", PROP_REDIS_TRUSTSTORE_PASSWORD),
            Map.entry("TerrakubeRedisTruststorePasswordFile", PROP_REDIS_TRUSTSTORE_PASSWORD),
            Map.entry("io.terrakube.executor.redis.sentinel.passwordFile", PROP_REDIS_SENTINEL_PASSWORD),
            Map.entry("TerrakubeRedisSentinelPasswordFile", PROP_REDIS_SENTINEL_PASSWORD)
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
        Set<String> explicitFilesToSkip = getExplicitFilePaths(resolver);

        File secretsDir = loadConfiguredDirectory(resolver, secrets, explicitFilesToSkip);
        loadLegacyDirectory(secretsDir, secrets, explicitFilesToSkip);
        loadCombinedSecretsFile(resolver, secrets);
        loadIndividualSecretFiles(resolver, secrets);

        return secrets;
    }

    private static Set<String> getExplicitFilePaths(PropertyResolver resolver) {
        Set<String> explicitFiles = new HashSet<>();

        String combinedFilePath = resolver.getProperty("io.terrakube.executor.secrets.file");
        if (combinedFilePath == null || combinedFilePath.isBlank()) {
            combinedFilePath = resolver.getProperty("ExecutorSecretsFile");
        }
        if (combinedFilePath != null && !combinedFilePath.isBlank()) {
            explicitFiles.add(getCanonicalOrAbsolutePath(new File(combinedFilePath)));
        }

        for (String fileProperty : FILE_PROPERTY_TO_CANONICAL_PROPERTY.keySet()) {
            String filePath = resolver.getProperty(fileProperty);
            if (filePath != null && !filePath.isBlank()) {
                explicitFiles.add(getCanonicalOrAbsolutePath(new File(filePath)));
            }
        }

        return explicitFiles;
    }

    private static File loadConfiguredDirectory(PropertyResolver resolver, Map<String, Object> secrets, Set<String> explicitFilesToSkip) {
        String configuredDir = resolver.getProperty("io.terrakube.executor.secrets.directory");
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = resolver.getProperty("ExecutorSecretsDirectory");
        }
        boolean isExplicit = configuredDir != null && !configuredDir.isBlank();
        String directoryPath = isExplicit ? configuredDir : DEFAULT_SECRETS_DIRECTORY;
        File secretsDir = new File(directoryPath);
        if (secretsDir.exists() && secretsDir.isDirectory()) {
            loadSecretsFromDirectory(secretsDir, secrets, explicitFilesToSkip);
        } else if (isExplicit) {
            throw new IllegalStateException("Configured ephemeral secrets directory does not exist or is not a directory: " + configuredDir);
        }
        return secretsDir;
    }

    private static void loadLegacyDirectory(File secretsDir, Map<String, Object> secrets, Set<String> explicitFilesToSkip) {
        File legacyDir = new File(LEGACY_INTERNAL_SECRET_DIRECTORY);
        if (legacyDir.exists() && legacyDir.isDirectory() && !legacyDir.equals(secretsDir)) {
            loadSecretsFromDirectory(legacyDir, secrets, explicitFilesToSkip);
        }
    }

    private static void loadCombinedSecretsFile(PropertyResolver resolver, Map<String, Object> secrets) {
        String combinedFilePath = resolver.getProperty("io.terrakube.executor.secrets.file");
        if (combinedFilePath == null || combinedFilePath.isBlank()) {
            combinedFilePath = resolver.getProperty("ExecutorSecretsFile");
        }
        if (combinedFilePath != null && !combinedFilePath.isBlank()) {
            File combinedFile = new File(combinedFilePath);
            if (combinedFile.exists() && combinedFile.isFile()) {
                loadEnvFile(combinedFile, secrets);
            } else {
                throw new IllegalStateException("Configured ephemeral secrets file does not exist or is not a file: " + combinedFilePath);
            }
        }
    }

    private static void loadIndividualSecretFiles(PropertyResolver resolver, Map<String, Object> secrets) {
        Set<String> processedFiles = new HashSet<>();
        for (Map.Entry<String, String> entry : FILE_PROPERTY_TO_CANONICAL_PROPERTY.entrySet()) {
            String fileProperty = entry.getKey();
            String canonicalProperty = entry.getValue();
            String filePath = resolver.getProperty(fileProperty);
            if (filePath != null && !filePath.isBlank()) {
                File file = new File(filePath);
                String pathKey = getCanonicalOrAbsolutePath(file);
                if (processedFiles.contains(pathKey)) {
                    log.debug("Secret file for property {} already loaded: {}", fileProperty, filePath);
                    continue;
                }
                if (file.exists() && file.isFile()) {
                    processedFiles.add(pathKey);
                    try {
                        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                        registerSecret(secrets, canonicalProperty, content);
                        shredAndDeleteFile(file);
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to read secret file for property " + fileProperty + " (" + filePath + "): " + e.getMessage(), e);
                    }
                } else {
                    throw new IllegalStateException("Configured secret file for property " + fileProperty + " does not exist: " + filePath);
                }
            }
        }
    }

    public static void loadSecretsFromDirectory(File directory, Map<String, Object> secrets) {
        loadSecretsFromDirectory(directory, secrets, Set.of());
    }

    private static void loadSecretsFromDirectory(File directory, Map<String, Object> secrets, Set<String> explicitFilesToSkip) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile() || file.isHidden() || file.getName().startsWith(".")) {
                continue;
            }
            String pathKey = getCanonicalOrAbsolutePath(file);
            if (explicitFilesToSkip.contains(pathKey)) {
                log.debug("Skipping explicit secret file during directory pass: {}", file.getAbsolutePath());
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
                    throw new IllegalStateException("Failed to read secret file " + file.getAbsolutePath() + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private static String getCanonicalOrAbsolutePath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    public static void loadEnvFile(File file, Map<String, Object> secrets) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                parseEnvLine(line, secrets);
            }
            shredAndDeleteFile(file);
        } catch (Exception e) {
            log.error("Failed to parse secret env file {}: {}", file.getAbsolutePath(), e.getMessage());
            throw new IllegalStateException("Failed to parse secret env file " + file.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static void parseEnvLine(String line, Map<String, Object> secrets) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        int eqIndex = trimmed.indexOf('=');
        if (eqIndex > 0) {
            String key = trimmed.substring(0, eqIndex).trim();
            String value = stripQuotes(trimmed.substring(eqIndex + 1).trim());
            registerSecret(secrets, key, value);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static void registerSecret(Map<String, Object> map, String rawKey, String value) {
        if (rawKey == null || rawKey.isBlank() || value == null) {
            return;
        }
        String trimmedKey = rawKey.trim();
        String trimmedVal = value.trim();

        if (SECRET_KEY_TO_CANONICAL_PROPERTY.containsKey(trimmedKey)) {
            map.put(trimmedKey, trimmedVal);
            map.put(SECRET_KEY_TO_CANONICAL_PROPERTY.get(trimmedKey), trimmedVal);
        } else if (CANONICAL_PROPERTY_TO_SECRET_KEY.containsKey(trimmedKey)) {
            map.put(trimmedKey, trimmedVal);
            map.put(CANONICAL_PROPERTY_TO_SECRET_KEY.get(trimmedKey), trimmedVal);
        } else if (KNOWN_OUTPUT_SECRET_KEYS.contains(trimmedKey)) {
            map.put(trimmedKey, trimmedVal);
        } else {
            log.debug("Ignoring unrecognized ephemeral secret key: {}", trimmedKey);
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
            } catch (Exception ex) {
                // Best-effort cleanup; file may be locked or already removed
                log.trace("Suppressed secondary deletion failure for {}: {}", file.getAbsolutePath(), ex.getMessage());
            }
        }
    }
}
