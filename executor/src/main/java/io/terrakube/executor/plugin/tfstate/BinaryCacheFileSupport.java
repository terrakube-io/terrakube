package io.terrakube.executor.plugin.tfstate;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Shared filesystem staging helpers for cached tool binaries restored from state backends.
 */
@Slf4j
public final class BinaryCacheFileSupport {

    private BinaryCacheFileSupport() {
    }

    /**
     * Ensure {@code targetFile}'s parent directory exists and return a non-existent unique sibling temp path.
     */
    public static Path prepareTempTarget(File targetFile) throws IOException {
        Path targetPath = targetFile.toPath();
        Path parentDir = targetPath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        String tempName = targetPath.getFileName().toString() + "." + UUID.randomUUID() + ".tmp";
        return parentDir != null ? parentDir.resolve(tempName) : Path.of(tempName);
    }

    /**
     * Mark the downloaded temp binary executable and atomically move it into {@code targetFile}.
     */
    public static void finalizeDownloadedBinary(Path tempFile, File targetFile, String label) throws IOException {
        if (!tempFile.toFile().setExecutable(true, true)) {
            log.warn("Failed to set executable permission on restored {} binary", label);
        }
        Path targetPath = targetFile.toPath();
        try {
            Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!targetFile.setExecutable(true, true)) {
            log.warn("Failed to set executable permission on restored {} binary", label);
        }
    }

    public static void deleteQuietly(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // Best-effort cleanup of staging file
        }
    }
}
