package io.terrakube.executor.service.infracost;

import io.terrakube.executor.configuration.InfracostExecutorProperties;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class InfracostBinaryService {

    public static final String DEFAULT_INFRACOST_VERSION = InfracostExecutorProperties.DEFAULT_VERSION;
    private static final String DEFAULT_RELEASE_BASE_URL = "https://github.com/infracost/infracost/releases/download";
    private static final String REDIS_UPLOAD_LOCK_PREFIX = "infracost-binary-uploading:";
    private static final Duration REDIS_UPLOAD_LOCK_TTL = Duration.ofSeconds(120);
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^[0-9]++(?:\\.[0-9]++)*+(?:-[a-zA-Z0-9.]++)?+$");
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("\\b([a-fA-F0-9]{64})\\b");
    private static final Pattern SHA1_HEX_PATTERN = Pattern.compile("\\b([a-fA-F0-9]{40})\\b");

    private final TerraformState terraformState;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String defaultVersion;
    private final String userHomeDirectory;
    private final String releaseBaseUrl;
    private final Function<String, File> pathLookup;
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public InfracostBinaryService(
            TerraformState terraformState,
            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
            @Autowired(required = false) InfracostExecutorProperties properties) {
        this(
                terraformState,
                redisTemplate,
                properties != null ? properties.getDefaultVersion() : DEFAULT_INFRACOST_VERSION,
                FileUtils.getUserDirectoryPath(),
                DEFAULT_RELEASE_BASE_URL,
                InfracostBinaryService::findExecutableOnPath
        );
    }

    InfracostBinaryService(
            TerraformState terraformState,
            RedisTemplate<String, Object> redisTemplate,
            String defaultVersion,
            String userHomeDirectory,
            String releaseBaseUrl,
            Function<String, File> pathLookup) {
        this.terraformState = terraformState;
        this.redisTemplate = redisTemplate;
        this.defaultVersion = (defaultVersion != null && !defaultVersion.isBlank())
                ? defaultVersion.trim()
                : DEFAULT_INFRACOST_VERSION;
        this.userHomeDirectory = userHomeDirectory;
        this.releaseBaseUrl = releaseBaseUrl.endsWith("/")
                ? releaseBaseUrl.substring(0, releaseBaseUrl.length() - 1)
                : releaseBaseUrl;
        this.pathLookup = pathLookup;
    }

    public String normalizeAndValidateVersion(String version) {
        String effective = (version != null && !version.isBlank()) ? version.trim() : this.defaultVersion;
        if (effective == null || effective.isBlank()) {
            effective = DEFAULT_INFRACOST_VERSION;
        }
        if (effective.startsWith("v") || effective.startsWith("V")) {
            effective = effective.substring(1).trim();
        }
        if (!SEMVER_PATTERN.matcher(effective).matches()) {
            throw new IllegalArgumentException(String.format(
                    "Invalid Infracost version format: '%s'. Expected semantic version (e.g. 0.10.50).", version));
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

    public static String getArchiveFilename(String os, String arch) {
        return String.format("infracost-%s-%s.tar.gz", os, arch);
    }

    public File getInfracostBinary(String version) {
        String os = resolveOs();
        String arch = resolveArchitecture();
        String binaryFileName = "windows".equals(os) ? "infracost.exe" : "infracost";

        if (version == null || version.isBlank()) {
            File pathBinary = pathLookup != null ? pathLookup.apply(binaryFileName) : null;
            if (pathBinary != null && pathBinary.isFile() && pathBinary.canExecute()) {
                log.info("Using Infracost binary from system PATH: {}", pathBinary.getAbsolutePath());
                return pathBinary;
            }
        }

        String effectiveVersion = normalizeAndValidateVersion(version);
        ReentrantLock lock = locks.computeIfAbsent(effectiveVersion + ":" + os + "_" + arch, key -> new ReentrantLock());
        lock.lock();
        try {
            return resolveManagedBinary(effectiveVersion, os, arch, binaryFileName);
        } finally {
            lock.unlock();
        }
    }

    private File resolveManagedBinary(String effectiveVersion, String os, String arch, String binaryFileName) {
        String cacheDirPath = String.format("%s/.terrakube/bin/infracost/%s/%s_%s",
                userHomeDirectory, effectiveVersion, os, arch);
        File cacheDir = new File(FilenameUtils.separatorsToSystem(cacheDirPath));
        File localBinary = new File(cacheDir, binaryFileName);
        File localChecksumFile = new File(cacheDir, "infracost.sha1");

        // 1. Validate local cache with SHA-1 sidecar
        if (localBinary.exists() || localChecksumFile.exists()) {
            if (isValidBinaryWithChecksum(localBinary, localChecksumFile)) {
                log.info("Found SHA-1 verified Infracost binary in local cache: {}", localBinary.getAbsolutePath());
                return localBinary;
            }
            log.warn("Local cached Infracost binary failed SHA-1 verification for {} ({}_{}); deleting cached binary",
                    effectiveVersion, os, arch);
            deleteCachedFiles(localBinary, localChecksumFile);
        }

        ensureDirectoryExists(cacheDir);

        // 2. Restore from storage cache and validate SHA-1 against storage sidecar
        boolean forceStorageRefresh = false;
        log.info("Attempting to download Infracost binary version {} ({}_{}) from storage cache", effectiveVersion, os, arch);
        if (terraformState != null && terraformState.downloadInfracostBinary(effectiveVersion, os, arch, localBinary)) {
            Optional<String> expectedSha1 = terraformState.downloadInfracostChecksum(effectiveVersion, os, arch)
                    .flatMap(InfracostBinaryService::extractSha1Hex);
            if (expectedSha1.isPresent() && verifyBinaryFileMatchesSha1(localBinary, expectedSha1.get())) {
                writeChecksumFile(localChecksumFile, expectedSha1.get());
                log.info("Restored SHA-1 verified Infracost binary version {} ({}_{}) from storage cache",
                        effectiveVersion, os, arch);
                return localBinary;
            }
            log.warn("Infracost binary downloaded from storage failed SHA-1 verification for {} ({}_{}); "
                    + "deleting cached binary and downloading fresh from upstream", effectiveVersion, os, arch);
            deleteCachedFiles(localBinary, localChecksumFile);
            forceStorageRefresh = true;
        } else {
            deleteCachedFiles(localBinary, localChecksumFile);
        }

        // 3. Download upstream tarball, verify SHA-256, extract, write SHA-1 sidecar, upload to storage
        downloadAndInstallFromUpstream(effectiveVersion, os, arch, localBinary, localChecksumFile);
        uploadWithLock(effectiveVersion, os, arch, localBinary, localChecksumFile, forceStorageRefresh);
        return localBinary;
    }

    private void downloadAndInstallFromUpstream(
            String version,
            String os,
            String arch,
            File targetBinary,
            File targetChecksumFile) {
        String archiveName = getArchiveFilename(os, arch);
        String archiveUrl = String.format("%s/v%s/%s", releaseBaseUrl, version, archiveName);
        String checksumUrl = archiveUrl + ".sha256";

        Path tempDir = null;
        try {
            ensureDirectoryExists(targetBinary.getParentFile());
            tempDir = Files.createTempDirectory(targetBinary.getParentFile().toPath(), "infracost-dl-");
            File archiveFile = tempDir.resolve(archiveName).toFile();
            File sha256File = tempDir.resolve(archiveName + ".sha256").toFile();

            log.info("Downloading Infracost release tarball from {}", archiveUrl);
            FileUtils.copyURLToFile(URI.create(archiveUrl).toURL(), archiveFile, 30_000, 120_000);
            FileUtils.copyURLToFile(URI.create(checksumUrl).toURL(), sha256File, 30_000, 30_000);

            String expectedSha256 = extractSha256Hex(FileUtils.readFileToString(sha256File, StandardCharsets.UTF_8))
                    .orElseThrow(() -> new IllegalStateException("Missing SHA-256 digest in " + checksumUrl));
            String actualSha256 = computeDigestHex(archiveFile, "SHA-256");
            if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                throw new IllegalStateException(String.format(
                        "Infracost tarball SHA-256 verification failed for v%s: expected=%s actual=%s",
                        version, expectedSha256, actualSha256));
            }

            File extractedTemp = tempDir.resolve(targetBinary.getName() + ".part").toFile();
            extractInfracostExecutable(archiveFile, os, arch, extractedTemp);

            if (!extractedTemp.setExecutable(true, true)) {
                log.warn("Failed to mark extracted Infracost binary executable: {}", extractedTemp.getAbsolutePath());
            }

            Files.move(extractedTemp.toPath(), targetBinary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!targetBinary.setExecutable(true, true)) {
                log.warn("Failed to mark Infracost binary executable: {}", targetBinary.getAbsolutePath());
            }

            String binarySha1 = computeDigestHex(targetBinary, "SHA-1");
            writeChecksumFile(targetChecksumFile, binarySha1);
            log.info("Installed Infracost binary v{} ({}_{}) locally with SHA-1 {}", version, os, arch, binarySha1);
        } catch (IOException e) {
            deleteCachedFiles(targetBinary, targetChecksumFile);
            throw new IllegalStateException("Failed to download or extract Infracost binary v" + version, e);
        } catch (RuntimeException e) {
            deleteCachedFiles(targetBinary, targetChecksumFile);
            throw e;
        } finally {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    private void extractInfracostExecutable(File archiveFile, String os, String arch, File destination) throws IOException {
        String expectedBase = "windows".equals(os) ? "infracost.exe" : "infracost";
        String expectedAlt = String.format("infracost-%s-%s%s", os, arch, "windows".equals(os) ? ".exe" : "");

        try (InputStream fileIn = Files.newInputStream(archiveFile.toPath());
             BufferedInputStream bufferedIn = new BufferedInputStream(fileIn);
             GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(bufferedIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.contains("..")) {
                    throw new IllegalStateException("Unsafe archive entry path in Infracost tarball: " + entryName);
                }
                String baseName = FilenameUtils.getName(entryName);
                if (baseName.equals(expectedBase) || baseName.equals(expectedAlt)) {
                    Files.copy(tarIn, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    if (destination.length() == 0) {
                        throw new IllegalStateException("Extracted Infracost binary is empty: " + entryName);
                    }
                    return;
                }
            }
        }
        throw new IllegalStateException("Expected Infracost binary entry not found inside " + archiveFile.getName());
    }

    private void uploadWithLock(
            String version,
            String os,
            String arch,
            File binaryFile,
            File checksumFile,
            boolean forceRefresh) {
        if (terraformState == null) {
            return;
        }
        String lockKey = REDIS_UPLOAD_LOCK_PREFIX + version + ":" + os + "_" + arch;
        boolean acquiredLock = forceRefresh;

        if (!acquiredLock) {
            try {
                if (redisTemplate != null && redisTemplate.opsForValue() != null) {
                    Boolean setSuccess = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", REDIS_UPLOAD_LOCK_TTL);
                    acquiredLock = Boolean.TRUE.equals(setSuccess);
                } else {
                    acquiredLock = true;
                }
            } catch (Exception e) {
                log.warn("Redis advisory lock check failed for Infracost upload (fail-open): {}", e.getMessage());
                acquiredLock = true;
            }
        }

        if (!acquiredLock) {
            log.info("Advisory lock [{}] held by another runner. Skipping Infracost storage upload", lockKey);
            return;
        }

        try {
            String sha1 = FileUtils.readFileToString(checksumFile, StandardCharsets.UTF_8).trim();
            if (terraformState.saveInfracostBinary(version, os, arch, binaryFile)) {
                terraformState.saveInfracostChecksum(version, os, arch, sha1);
            }
        } catch (Exception e) {
            log.warn("Failed to cache Infracost binary and SHA-1 in storage: {}", e.getMessage());
        }
    }

    private static boolean isValidBinaryWithChecksum(File binaryFile, File checksumFile) {
        if (!checksumFile.isFile()) {
            return false;
        }
        try {
            Optional<String> expectedSha1 = extractSha1Hex(FileUtils.readFileToString(checksumFile, StandardCharsets.UTF_8));
            return expectedSha1.isPresent() && verifyBinaryFileMatchesSha1(binaryFile, expectedSha1.get());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean verifyBinaryFileMatchesSha1(File binaryFile, String expectedSha1) {
        if (binaryFile == null || !binaryFile.isFile() || binaryFile.length() == 0) {
            return false;
        }
        if (!binaryFile.canExecute() && !binaryFile.setExecutable(true, true)) {
            return false;
        }
        try {
            String actualSha1 = computeDigestHex(binaryFile, "SHA-1");
            return expectedSha1.equalsIgnoreCase(actualSha1);
        } catch (IOException e) {
            return false;
        }
    }

    static String computeDigestHex(File file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            try (InputStream in = new FileInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest algorithm unavailable: " + algorithm, e);
        }
    }

    private static Optional<String> extractSha1Hex(String content) {
        return extractHexToken(content, SHA1_HEX_PATTERN);
    }

    private static Optional<String> extractSha256Hex(String content) {
        return extractHexToken(content, SHA256_HEX_PATTERN);
    }

    private static Optional<String> extractHexToken(String content, Pattern pattern) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(content.trim());
        return matcher.find() ? Optional.of(matcher.group(1).toLowerCase()) : Optional.empty();
    }

    private static void writeChecksumFile(File checksumFile, String sha1Hex) {
        try {
            FileUtils.writeStringToFile(checksumFile, sha1Hex + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write local Infracost SHA-1 sidecar {}: {}", checksumFile.getAbsolutePath(), e.getMessage());
        }
    }

    private static void deleteCachedFiles(File binaryFile, File checksumFile) {
        FileUtils.deleteQuietly(binaryFile);
        FileUtils.deleteQuietly(checksumFile);
    }

    private static void ensureDirectoryExists(File directory) {
        if (directory == null) {
            return;
        }
        try {
            FileUtils.forceMkdir(directory);
        } catch (IOException e) {
            log.warn("Failed to create Infracost cache directory {}: {}", directory.getAbsolutePath(), e.getMessage());
        }
    }

    static File findExecutableOnPath(String binaryName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir.trim(), binaryName);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate;
            }
        }
        return null;
    }
}
