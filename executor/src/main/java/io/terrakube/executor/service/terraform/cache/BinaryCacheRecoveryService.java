package io.terrakube.executor.service.terraform.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Self-heals a zero-byte or truncated Terraform/OpenTofu release archive left by an interrupted
 * download (issue #3621). {@code terraform-client} treats the archive's mere existence as a cache
 * hit, so without this it keeps failing until someone manually clears the pod's cache.
 *
 * <p>Called from {@code TerraformExecutorServiceImpl.ensureBinaryCached()}, after version
 * resolution and before the library's own download/extract. The library's download/extract, and
 * the S3 upload after a successful init, stay with the caller.
 */
@Slf4j
@Service
public class BinaryCacheRecoveryService {

    private static final String METRIC_INVALID = "terrakube.executor.binary.cache.invalid";
    private static final String METRIC_RECOVERY = "terrakube.executor.binary.cache.recovery";

    private final TerraformState terraformState;
    private final MeterRegistry meterRegistry;
    private final BinaryArchiveLocator locator;

    // Never evicted - the number of distinct product/version pairs one pod ever sees is small.
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    // Explicit: two constructors, neither primary, so Spring can't infer which to use.
    @Autowired
    public BinaryCacheRecoveryService(TerraformState terraformState, MeterRegistry meterRegistry) {
        this(terraformState, meterRegistry, new BinaryArchiveLocator(System.getProperty("user.home")));
    }

    /** Visible for tests, to point the locator at a temp directory instead of the real home. */
    BinaryCacheRecoveryService(TerraformState terraformState, MeterRegistry meterRegistry, BinaryArchiveLocator locator) {
        this.terraformState = terraformState;
        this.meterRegistry = meterRegistry;
        this.locator = locator;
    }

    /**
     * @param alreadyAvailable true if {@code binaryFile} is ready to use; false if the caller
     *                         still needs to run the library's own download/extract.
     * @param source           {@code existing-local}, {@code s3} or {@code local-download}.
     */
    public record Resolution(File binaryFile, boolean alreadyAvailable, String source) {
    }

    /**
     * Runs under this product/version's lock, so two jobs on the same pod never interleave
     * validation and cleanup for the same version. Not held across the actual download/extract
     * the caller runs afterwards - that's the library's own long-running work.
     */
    public Resolution resolve(String resolvedVersion, boolean tofu) throws BinaryCacheRecoveryException {
        String product = product(tofu);
        ReentrantLock lock = locks.computeIfAbsent(lockKey(product, resolvedVersion), key -> new ReentrantLock());
        lock.lock();
        try {
            return doResolve(resolvedVersion, tofu, product);
        } finally {
            lock.unlock();
        }
    }

    private Resolution doResolve(String resolvedVersion, boolean tofu, String product) throws BinaryCacheRecoveryException {
        BinaryArchiveLocator.Archive archive = locator.locate(resolvedVersion, tofu);
        File binaryFile = new File(archive.binaryVersionDirectory(), archive.executableName());

        // A valid local binary is used as-is; archives are never even inspected.
        if (isValidExecutable(binaryFile)) {
            recordValidated(product, resolvedVersion, "existing-local");
            return new Resolution(binaryFile, true, "existing-local");
        }
        if (binaryFile.exists()) {
            recordInvalid(product, InvalidArchiveReason.INCOMPLETE_BINARY);
            deleteVersionDirectory(archive, product, resolvedVersion, InvalidArchiveReason.INCOMPLETE_BINARY, "binary");
        }

        // Try S3, then trust it only once the restored file passes the same check.
        if (restoreFromCloudStorage(resolvedVersion, tofu, binaryFile, product)) {
            if (isValidExecutable(binaryFile)) {
                recordValidated(product, resolvedVersion, "s3");
                return new Resolution(binaryFile, true, "s3");
            }
            log.warn("S3-restored {} binary for version {} failed validation, discarding", product, resolvedVersion);
            deleteVersionDirectory(archive, product, resolvedVersion, null, "S3-restored binary");
        }

        // Nothing usable locally or from S3 - remove the archive only if it's actually the problem.
        InvalidArchiveReason archiveReason = validateArchive(archive.file(), archive.executableName());
        if (archiveReason != null) {
            recordInvalid(product, archiveReason);
            deleteFile(archive, product, resolvedVersion, archiveReason, "archive");
        }

        log.info("{} binary missing and local archive invalid; forcing fresh download: version={} os={} arch={}",
                capitalize(product), resolvedVersion, BinaryArchiveLocator.os(), BinaryArchiveLocator.arch());
        recordRecoveryResult(product, "local-download");
        return new Resolution(binaryFile, false, "local-download");
    }

    private boolean restoreFromCloudStorage(String resolvedVersion, boolean tofu, File binaryFile, String product) {
        try {
            return terraformState.downloadTerraformBinary(resolvedVersion, tofu, binaryFile);
        } catch (Exception e) {
            log.warn("S3 binary cache restore failed for {} {}: {}", product, resolvedVersion, e.getMessage());
            return false;
        }
    }

    /**
     * True when {@code file} is a regular, non-empty, executable file - used for local binaries,
     * S3 restores and before an S3 upload. A restore (zip extraction, S3 download) does not
     * always carry the executable bit over, so a file that is otherwise valid but missing it is
     * self-healed here rather than treated as another invalid-cache case to delete and redownload.
     */
    public boolean isValidExecutable(File file) {
        if (file == null || !file.isFile() || file.length() == 0) {
            return false;
        }
        if (!file.canExecute()) {
            file.setExecutable(true, true);
        }
        return file.canExecute();
    }

    /**
     * Invalid means: not a regular file, zero bytes, not a readable ZIP, or missing the expected
     * {@code tofu}/{@code terraform} entry. Null means either nothing to recover (first download)
     * or a valid archive whose binary just hasn't been extracted yet - left for the library as-is.
     */
    InvalidArchiveReason validateArchive(File archiveFile, String expectedEntryName) {
        if (!archiveFile.exists()) {
            return null;
        }
        if (!archiveFile.isFile()) {
            return InvalidArchiveReason.NOT_REGULAR_FILE;
        }
        if (archiveFile.length() == 0) {
            return InvalidArchiveReason.ZERO_BYTE;
        }
        try (ZipFile zip = new ZipFile(archiveFile)) {
            return zip.stream().anyMatch(entry -> !entry.isDirectory() && entryBaseName(entry).equals(expectedEntryName))
                    ? null
                    : InvalidArchiveReason.MISSING_EXECUTABLE;
        } catch (IOException e) {
            return InvalidArchiveReason.NOT_ZIP;
        }
    }

    private static String entryBaseName(ZipEntry entry) {
        String name = entry.getName();
        int lastSlash = name.lastIndexOf('/');
        return lastSlash < 0 ? name : name.substring(lastSlash + 1);
    }

    /** Deletes exactly {@code <product>/<resolvedVersion>/}, nothing more - see {@link #requireDirectChildOf}. */
    private void deleteVersionDirectory(BinaryArchiveLocator.Archive archive, String product, String resolvedVersion,
                                        InvalidArchiveReason reason, String label) throws BinaryCacheRecoveryException {
        File dir = archive.binaryVersionDirectory();
        if (!dir.exists()) {
            return;
        }
        requireDirectChildOf(archive.productDirectory(), dir, product, resolvedVersion);
        try {
            FileUtils.deleteDirectory(dir);
            logDeleted(product, dir, reason, label);
        } catch (IOException e) {
            recordRecoveryResult(product, "cleanup-failed");
            throw new BinaryCacheRecoveryException(String.format(
                    "Could not remove the invalid %s %s binary directory at %s; a stale artifact would keep failing the same way, so manual cleanup is required",
                    product, resolvedVersion, dir), e);
        }
    }

    private void deleteFile(BinaryArchiveLocator.Archive archive, String product, String resolvedVersion,
                            InvalidArchiveReason reason, String label) throws BinaryCacheRecoveryException {
        File file = archive.file();
        requireDirectChildOf(archive.downloadDirectory(), file, product, resolvedVersion);
        try {
            Files.deleteIfExists(file.toPath());
            logDeleted(product, file, reason, label);
        } catch (IOException e) {
            recordRecoveryResult(product, "cleanup-failed");
            throw new BinaryCacheRecoveryException(String.format(
                    "Could not remove the invalid %s %s %s at %s; a stale artifact would keep failing the same way, so manual cleanup is required",
                    product, resolvedVersion, label, file), e);
        }
    }

    private void logDeleted(String product, File target, InvalidArchiveReason reason, String label) {
        if (reason != null) {
            log.warn("Invalid {} {} detected: path={} reason={} action=deleted", product, label, target, reason.metricValue());
        } else {
            log.warn("Invalid {} {} detected: path={} action=deleted", product, label, target);
        }
    }

    /**
     * {@code resolvedVersion} should only ever be a real release version, but it still ends up in
     * a path built by string concatenation, so this checks rather than assumes: refuses to delete
     * anything that doesn't normalise to a direct child of {@code expectedParent}, in case a
     * {@code ..} or separator in the version string would otherwise escape the cache directory.
     */
    private void requireDirectChildOf(File expectedParent, File target, String product, String resolvedVersion) throws BinaryCacheRecoveryException {
        Path parentPath = expectedParent.toPath().toAbsolutePath().normalize();
        Path targetPath = target.toPath().toAbsolutePath().normalize();
        if (!parentPath.equals(targetPath.getParent())) {
            throw new BinaryCacheRecoveryException(String.format(
                    "Refusing to delete %s: it does not resolve to a direct child of the expected %s cache directory %s (version %s)",
                    target, product, expectedParent, resolvedVersion));
        }
    }

    private void recordValidated(String product, String resolvedVersion, String source) {
        log.info("{} binary cache validated: version={} source={}", capitalize(product), resolvedVersion, source);
        recordRecoveryResult(product, source);
    }

    private void recordInvalid(String product, InvalidArchiveReason reason) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_INVALID, "product", product, "reason", reason.metricValue()).increment();
        }
    }

    private void recordRecoveryResult(String product, String result) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_RECOVERY, "product", product, "result", result).increment();
        }
    }

    private static String product(boolean tofu) {
        return tofu ? "tofu" : "terraform";
    }

    private static String lockKey(String product, String resolvedVersion) {
        return product + ":" + resolvedVersion;
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
