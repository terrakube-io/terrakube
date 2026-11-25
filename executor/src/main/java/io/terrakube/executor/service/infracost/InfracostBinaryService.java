package io.terrakube.executor.service.infracost;

import io.terrakube.executor.configuration.InfracostExecutorProperties;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.common.BinaryPlatformUtils;
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
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
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
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("\\b([a-fA-F0-9]{64})\\b");
    private static final Pattern VERSION_OUTPUT_PATTERN = Pattern.compile("v?([0-9]+(?:\\.[0-9]+)+(?:-[a-zA-Z0-9.]+)?)");

    private record VerifiedBinaryEntry(long lastModifiedMillis, long sizeBytes, String sha256Hex) {
    }

    private final TerraformState terraformState;
    private final RedisTemplate<String, Object> redisTemplate;
    private final String configuredDefaultVersion;
    private final String defaultVersion;
    private final String userHomeDirectory;
    private final String releaseBaseUrl;
    private final Function<String, File> pathLookup;
    private final Function<File, Optional<String>> pathVersionProber;
    private final HttpClient httpClient;
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, VerifiedBinaryEntry> verifiedLocalHashes = new ConcurrentHashMap<>();

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
                InfracostBinaryService::findExecutableOnPath,
                InfracostBinaryService::probeBinaryVersion
        );
    }

    InfracostBinaryService(
            TerraformState terraformState,
            RedisTemplate<String, Object> redisTemplate,
            String defaultVersion,
            String userHomeDirectory,
            String releaseBaseUrl,
            Function<String, File> pathLookup) {
        this(
                terraformState,
                redisTemplate,
                defaultVersion,
                userHomeDirectory,
                releaseBaseUrl,
                pathLookup,
                InfracostBinaryService::probeBinaryVersion
        );
    }

    InfracostBinaryService(
            TerraformState terraformState,
            RedisTemplate<String, Object> redisTemplate,
            String defaultVersion,
            String userHomeDirectory,
            String releaseBaseUrl,
            Function<String, File> pathLookup,
            Function<File, Optional<String>> pathVersionProber) {
        this.terraformState = terraformState;
        this.redisTemplate = redisTemplate;
        this.configuredDefaultVersion = defaultVersion != null ? defaultVersion.trim() : "";
        this.defaultVersion = !this.configuredDefaultVersion.isBlank()
                ? this.configuredDefaultVersion
                : DEFAULT_INFRACOST_VERSION;
        this.userHomeDirectory = userHomeDirectory;
        this.releaseBaseUrl = releaseBaseUrl.endsWith("/")
                ? releaseBaseUrl.substring(0, releaseBaseUrl.length() - 1)
                : releaseBaseUrl;
        this.pathLookup = pathLookup;
        this.pathVersionProber = pathVersionProber != null ? pathVersionProber : InfracostBinaryService::probeBinaryVersion;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public String normalizeAndValidateVersion(String version) {
        return BinaryPlatformUtils.normalizeAndValidateVersion(
                version, this.defaultVersion, DEFAULT_INFRACOST_VERSION, "Infracost");
    }

    public static String resolveArchitecture() {
        return BinaryPlatformUtils.resolveArchitecture();
    }

    public static String resolveOs() {
        return BinaryPlatformUtils.resolveOs();
    }

    public static String getArchiveFilename(String os, String arch) {
        return String.format("infracost-%s-%s.tar.gz", os, arch);
    }

    public File getInfracostBinary(String version) {
        return getInfracostBinary(version, Instant.now().plusSeconds(180));
    }

    public File getInfracostBinary(String version, Instant deadline) {
        Instant effectiveDeadline = deadline != null ? deadline : Instant.now().plusSeconds(180);
        String os = resolveOs();
        String arch = resolveArchitecture();
        String binaryFileName = "windows".equals(os) ? "infracost.exe" : "infracost";
        String effectiveVersion = normalizeAndValidateVersion(version);

        if (version == null || version.isBlank()) {
            File pathBinary = resolveCompatiblePathBinary(binaryFileName, effectiveVersion);
            if (pathBinary != null) {
                return pathBinary;
            }
        }

        ReentrantLock lock = locks.computeIfAbsent(effectiveVersion + ":" + os + "_" + arch, key -> new ReentrantLock());
        long remainingMs = Duration.between(Instant.now(), effectiveDeadline).toMillis();
        if (remainingMs <= 0) {
            throw new IllegalStateException("Infracost binary resolution deadline exceeded before acquiring lock");
        }
        boolean acquired;
        try {
            acquired = lock.tryLock(remainingMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Infracost binary lock", e);
        }
        if (!acquired) {
            throw new IllegalStateException("Timed out waiting for Infracost binary lock within fail-open budget");
        }
        try {
            return resolveManagedBinary(effectiveVersion, os, arch, binaryFileName, effectiveDeadline);
        } finally {
            lock.unlock();
        }
    }

    private File resolveCompatiblePathBinary(String binaryFileName, String expectedVersion) {
        File pathBinary = pathLookup != null ? pathLookup.apply(binaryFileName) : null;
        if (pathBinary == null || !pathBinary.isFile() || !pathBinary.canExecute()) {
            return null;
        }
        if (configuredDefaultVersion == null || configuredDefaultVersion.isBlank()) {
            log.info("Using Infracost binary from system PATH (no default version configured): {}", pathBinary.getAbsolutePath());
            return pathBinary;
        }
        Optional<String> probed = pathVersionProber.apply(pathBinary);
        if (probed.isPresent() && expectedVersion.equals(probed.get())) {
            log.info("Using Infracost binary from system PATH matching version {}: {}", expectedVersion, pathBinary.getAbsolutePath());
            return pathBinary;
        }
        log.info("Ignoring Infracost binary on PATH ({}) because version [{}] does not match configured default [{}]",
                pathBinary.getAbsolutePath(), probed.orElse("unknown"), expectedVersion);
        return null;
    }

    private File resolveManagedBinary(
            String effectiveVersion,
            String os,
            String arch,
            String binaryFileName,
            Instant deadline) {
        String cacheDirPath = String.format("%s/.terrakube/bin/infracost/%s/%s_%s",
                userHomeDirectory, effectiveVersion, os, arch);
        File cacheDir = new File(FilenameUtils.separatorsToSystem(cacheDirPath));
        File localBinary = new File(cacheDir, binaryFileName);
        File localChecksumFile = new File(cacheDir, "infracost.sha256");

        // 1. Validate local cache with SHA-256 sidecar (memoized by mtime + size)
        if (localBinary.exists() || localChecksumFile.exists()) {
            if (isValidBinaryWithChecksum(localBinary, localChecksumFile)) {
                log.info("Found SHA-256 verified Infracost binary in local cache: {}", localBinary.getAbsolutePath());
                return localBinary;
            }
            log.warn("Local cached Infracost binary failed SHA-256 verification for {} ({}_{}); deleting cached binary",
                    effectiveVersion, os, arch);
            deleteCachedFiles(localBinary, localChecksumFile);
        }

        ensureDeadlineNotExceeded(deadline, "before storage cache restore");
        ensureDirectoryExists(cacheDir);

        // 2. Restore from storage cache and validate SHA-256 against storage sidecar
        boolean forceStorageRefresh = false;
        log.info("Attempting to download Infracost binary version {} ({}_{}) from storage cache", effectiveVersion, os, arch);
        if (terraformState != null && terraformState.downloadInfracostBinary(effectiveVersion, os, arch, localBinary)) {
            Optional<String> expectedSha256 = terraformState.downloadInfracostChecksum(effectiveVersion, os, arch)
                    .flatMap(InfracostBinaryService::extractSha256Hex);
            if (expectedSha256.isPresent() && verifyBinaryFileMatchesSha256(localBinary, expectedSha256.get())) {
                writeChecksumFile(localChecksumFile, expectedSha256.get());
                memoizeVerifiedBinary(localBinary, expectedSha256.get());
                log.info("Restored SHA-256 verified Infracost binary version {} ({}_{}) from storage cache",
                        effectiveVersion, os, arch);
                return localBinary;
            }
            log.warn("Infracost binary downloaded from storage failed SHA-256 verification for {} ({}_{}); "
                    + "deleting cached binary and downloading fresh from upstream", effectiveVersion, os, arch);
            deleteCachedFiles(localBinary, localChecksumFile);
            forceStorageRefresh = true;
        } else {
            deleteCachedFiles(localBinary, localChecksumFile);
        }

        ensureDeadlineNotExceeded(deadline, "before upstream release download");

        // 3. Download upstream tarball, verify SHA-256, extract, write SHA-256 sidecar, upload to storage
        downloadAndInstallFromUpstream(effectiveVersion, os, arch, localBinary, localChecksumFile, deadline);
        uploadWithLock(effectiveVersion, os, arch, localBinary, localChecksumFile, forceStorageRefresh, deadline);
        return localBinary;
    }

    private void downloadAndInstallFromUpstream(
            String version,
            String os,
            String arch,
            File targetBinary,
            File targetChecksumFile,
            Instant deadline) {
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
            downloadUrlToFile(URI.create(archiveUrl), archiveFile, deadline);
            downloadUrlToFile(URI.create(checksumUrl), sha256File, deadline);

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

            String binarySha256 = computeDigestHex(targetBinary, "SHA-256");
            writeChecksumFile(targetChecksumFile, binarySha256);
            memoizeVerifiedBinary(targetBinary, binarySha256);
            log.info("Installed Infracost binary v{} ({}_{}) locally with SHA-256 {}", version, os, arch, binarySha256);
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

    private void downloadUrlToFile(URI uri, File destination, Instant deadline) throws IOException {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IOException("Infracost binary download exceeded fail-open deadline");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(remaining)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                try (InputStream body = response.body()) {
                    // close error stream
                }
                throw new IOException("Unexpected HTTP " + statusCode + " downloading " + uri);
            }
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(destination.toPath())) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Instant.now().isAfter(deadline)) {
                        throw new IOException("Infracost binary download exceeded fail-open deadline");
                    }
                    out.write(buffer, 0, read);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + uri, e);
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
            boolean forceRefresh,
            Instant deadline) {
        if (terraformState == null || Instant.now().isAfter(deadline)) {
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
            String sha256 = FileUtils.readFileToString(checksumFile, StandardCharsets.UTF_8).trim();
            if (terraformState.saveInfracostBinary(version, os, arch, binaryFile)) {
                terraformState.saveInfracostChecksum(version, os, arch, sha256);
            }
        } catch (Exception e) {
            log.warn("Failed to cache Infracost binary and SHA-256 in storage: {}", e.getMessage());
        }
    }

    private boolean isValidBinaryWithChecksum(File binaryFile, File checksumFile) {
        if (!checksumFile.isFile()) {
            return false;
        }
        try {
            Optional<String> expectedSha256 = extractSha256Hex(FileUtils.readFileToString(checksumFile, StandardCharsets.UTF_8));
            if (expectedSha256.isEmpty()) {
                return false;
            }
            String expected = expectedSha256.get();
            if (isMemoizedMatch(binaryFile, expected)) {
                return binaryFile.canExecute() || binaryFile.setExecutable(true, true);
            }
            if (verifyBinaryFileMatchesSha256(binaryFile, expected)) {
                memoizeVerifiedBinary(binaryFile, expected);
                return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isMemoizedMatch(File binaryFile, String expectedSha256) {
        if (binaryFile == null || !binaryFile.isFile()) {
            return false;
        }
        Path path = binaryFile.toPath().toAbsolutePath().normalize();
        VerifiedBinaryEntry cached = verifiedLocalHashes.get(path);
        return cached != null
                && cached.lastModifiedMillis() == binaryFile.lastModified()
                && cached.sizeBytes() == binaryFile.length()
                && cached.sha256Hex().equalsIgnoreCase(expectedSha256);
    }

    private void memoizeVerifiedBinary(File binaryFile, String sha256Hex) {
        if (binaryFile == null || !binaryFile.isFile()) {
            return;
        }
        Path path = binaryFile.toPath().toAbsolutePath().normalize();
        verifiedLocalHashes.put(path, new VerifiedBinaryEntry(
                binaryFile.lastModified(),
                binaryFile.length(),
                sha256Hex.toLowerCase(Locale.ROOT)));
    }

    private static boolean verifyBinaryFileMatchesSha256(File binaryFile, String expectedSha256) {
        if (binaryFile == null || !binaryFile.isFile() || binaryFile.length() == 0) {
            return false;
        }
        if (!binaryFile.canExecute() && !binaryFile.setExecutable(true, true)) {
            return false;
        }
        try {
            String actualSha256 = computeDigestHex(binaryFile, "SHA-256");
            return expectedSha256.equalsIgnoreCase(actualSha256);
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

    private static Optional<String> extractSha256Hex(String content) {
        return extractHexToken(content, SHA256_HEX_PATTERN);
    }

    private static Optional<String> extractHexToken(String content, Pattern pattern) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(content.trim());
        return matcher.find() ? Optional.of(matcher.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    private static void writeChecksumFile(File checksumFile, String sha256Hex) {
        try {
            FileUtils.writeStringToFile(checksumFile, sha256Hex + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write local Infracost SHA-256 sidecar {}: {}", checksumFile.getAbsolutePath(), e.getMessage());
        }
    }

    private void deleteCachedFiles(File binaryFile, File checksumFile) {
        if (binaryFile != null) {
            verifiedLocalHashes.remove(binaryFile.toPath().toAbsolutePath().normalize());
        }
        FileUtils.deleteQuietly(binaryFile);
        FileUtils.deleteQuietly(checksumFile);
    }

    private static void ensureDeadlineNotExceeded(Instant deadline, String stage) {
        if (deadline != null && Instant.now().isAfter(deadline)) {
            throw new IllegalStateException("Infracost fail-open deadline exceeded " + stage);
        }
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

    static Optional<String> probeBinaryVersion(File binary) {
        try {
            Process p = new ProcessBuilder(binary.getAbsolutePath(), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return Optional.empty();
            }
            if (p.exitValue() != 0) {
                return Optional.empty();
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            Matcher matcher = VERSION_OUTPUT_PATTERN.matcher(output);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (Exception e) {
            log.debug("Failed to probe Infracost binary version {}: {}", binary.getAbsolutePath(), e.getMessage());
            return Optional.empty();
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
