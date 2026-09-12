package io.terrakube.executor.service.opa;

import io.terrakube.executor.plugin.tfstate.TerraformState;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OpaBinaryService {

    private static final String DEFAULT_OPA_VERSION = "1.20.2";
    private static final String REDIS_OPA_UPLOAD_LOCK_PREFIX = "opa-binary-uploading:";
    private static final Duration REDIS_OPA_UPLOAD_LOCK_TTL = Duration.ofSeconds(120);
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]+)*(-[a-zA-Z0-9.]+)?$");

    private final TerraformState terraformState;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String defaultOpaVersion;

    @Autowired
    public OpaBinaryService(
            TerraformState terraformState,
            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
            @Value("${io.terrakube.executor.opa.default-version:1.20.2}") String defaultOpaVersion) {
        this.terraformState = terraformState;
        this.redisTemplate = redisTemplate;
        this.defaultOpaVersion = (defaultOpaVersion != null && !defaultOpaVersion.isBlank())
                ? defaultOpaVersion.trim()
                : DEFAULT_OPA_VERSION;
    }

    public OpaBinaryService(TerraformState terraformState, RedisTemplate<String, Object> redisTemplate) {
        this(terraformState, redisTemplate, DEFAULT_OPA_VERSION);
    }

    public String normalizeAndValidateVersion(String version) {
        String effective = (version != null && !version.isBlank()) ? version.trim() : this.defaultOpaVersion;
        if (effective == null || effective.isBlank()) {
            effective = DEFAULT_OPA_VERSION;
        }

        // Strip leading 'v' or 'V' if present
        if (effective.startsWith("v") || effective.startsWith("V")) {
            effective = effective.substring(1).trim();
        }

        if (!SEMVER_PATTERN.matcher(effective).matches()) {
            throw new IllegalArgumentException(String.format(
                    "Invalid OPA version format: '%s'. Expected semantic version (e.g. 1.20.2, 0.68.0).", version));
        }

        return effective;
    }

    public static String resolveArchitecture() {
        String arch = System.getProperty("os.arch", "amd64").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("armv8")) {
            return "arm64";
        }
        return "amd64";
    }

    public static String resolveOs() {
        String os = System.getProperty("os.name", "linux").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        } else if (os.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    public static String getOpaBinaryFilename(String os, String arch) {
        String extension = "windows".equals(os) ? ".exe" : "";
        return String.format("opa_%s_%s%s", os, arch, extension);
    }

    public File getOpaBinary(String version) {
        String effectiveVersion = normalizeAndValidateVersion(version);
        String os = resolveOs();
        String arch = resolveArchitecture();
        String binaryFileName = "windows".equals(os) ? "opa.exe" : "opa";

        // 1. Local Cache: ~/.terrakube/bin/opa/<version>/<os>_<arch>/opa
        String userHome = FileUtils.getUserDirectoryPath();
        String localCachePath = String.format("%s/.terrakube/bin/opa/%s/%s_%s/%s", userHome, effectiveVersion, os, arch, binaryFileName);
        File localBinary = new File(FilenameUtils.separatorsToSystem(localCachePath));

        if (localBinary.exists() && localBinary.canExecute()) {
            log.info("Found cached OPA binary locally: {}", localBinary.getAbsolutePath());
            return localBinary;
        }

        // Ensure parent directory exists
        try {
            FileUtils.forceMkdirParent(localBinary);
        } catch (IOException e) {
            log.warn("Failed to create directory for OPA binary: {}", e.getMessage());
        }

        // 2. Cloud Storage Cache
        log.info("Attempting to download OPA binary version {} ({}_{}) from cloud storage", effectiveVersion, os, arch);
        if (terraformState.downloadOpaBinary(effectiveVersion, os, arch, localBinary)) {
            if (localBinary.setExecutable(true, true)) {
                log.info("Restored OPA binary version {} from cloud storage", effectiveVersion);
                return localBinary;
            }
        }

        // 3. Upstream Download & Advisory Redis Lock
        log.info("Downloading OPA binary version {} ({}_{}) from upstream", effectiveVersion, os, arch);
        downloadUpstreamOpaBinary(effectiveVersion, os, arch, localBinary);

        if (!localBinary.setExecutable(true, true)) {
            log.warn("Failed to mark OPA binary as executable: {}", localBinary.getAbsolutePath());
        }

        // Advisory upload to cloud storage
        uploadOpaBinaryWithLock(effectiveVersion, os, arch, localBinary);

        return localBinary;
    }

    private void downloadUpstreamOpaBinary(String version, String os, String arch, File targetFile) {
        String downloadName = getOpaBinaryFilename(os, arch);
        String primaryUrl = String.format("https://openpolicyagent.org/downloads/v%s/%s", version, downloadName);
        String fallbackUrl = String.format("https://github.com/open-policy-agent/opa/releases/download/v%s/%s", version, downloadName);

        try {
            log.info("Downloading OPA from {}", primaryUrl);
            FileUtils.copyURLToFile(URI.create(primaryUrl).toURL(), targetFile, 30000, 60000);
            return;
        } catch (Exception e) {
            log.warn("Failed to download OPA from primary URL ({}): {}. Trying fallback URL.", primaryUrl, e.getMessage());
        }

        try {
            log.info("Downloading OPA from fallback {}", fallbackUrl);
            FileUtils.copyURLToFile(URI.create(fallbackUrl).toURL(), targetFile, 30000, 60000);
        } catch (Exception e) {
            log.error("Failed to download OPA from fallback URL ({}): {}", fallbackUrl, e.getMessage(), e);
            throw new IllegalStateException("Failed to download OPA binary for version " + version, e);
        }
    }

    private void uploadOpaBinaryWithLock(String version, String os, String arch, File binaryFile) {
        String lockKey = REDIS_OPA_UPLOAD_LOCK_PREFIX + version + ":" + os + "_" + arch;
        boolean acquiredLock = false;

        try {
            if (redisTemplate != null && redisTemplate.opsForValue() != null) {
                Boolean setSuccess = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", REDIS_OPA_UPLOAD_LOCK_TTL);
                acquiredLock = Boolean.TRUE.equals(setSuccess);
            } else {
                // If Redis is not configured, still upload
                acquiredLock = true;
            }
        } catch (Exception e) {
            log.warn("Redis advisory lock check failed for OPA upload (fail-open): {}", e.getMessage());
            acquiredLock = true;
        }

        if (acquiredLock) {
            log.info("Acquired advisory lock [{}]. Uploading OPA binary to cloud storage", lockKey);
            try {
                terraformState.saveOpaBinary(version, os, arch, binaryFile);
            } catch (Exception e) {
                log.warn("Failed to save OPA binary to cloud storage: {}", e.getMessage());
            }
        } else {
            log.info("Advisory lock [{}] held by another runner. Skipping upload to cloud storage", lockKey);
        }
    }
}
