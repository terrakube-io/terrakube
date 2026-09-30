package io.terrakube.executor.service.terraform.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.terraform.TerraformDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs against the real {@code terraform-client} library (real download, real ZIP) instead of
 * mocks, proving {@link BinaryArchiveLocator}'s paths match the library's own and that recovery
 * lets it redownload cleanly. Needs network access, so it's not named {@code *Test} and
 * {@code mvn test} skips it by default.
 *
 * <p>Run explicitly: {@code mvn -pl executor test -Dtest=BinaryCacheRecoveryRealDownloaderCheck}
 */
class BinaryCacheRecoveryRealDownloaderCheck {

    private static final String VERSION = "1.9.0"; // small, old, fast to fetch

    @Test
    void reproducesTheIssueAgainstMainThenShowsRecoveryFixesIt(@TempDir Path home) throws Exception {
        String previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            TerraformDownloader downloader = new TerraformDownloader();
            BinaryArchiveLocator locator = new BinaryArchiveLocator(home.toString());

            // Real download - proves the locator's path matches the library's own.
            String firstBinaryPath = downloader.downloadTerraformVersion(VERSION);
            File archive = locator.locate(VERSION, false).file();
            File binary = new File(firstBinaryPath);
            assertTrue(archive.isFile(), "locator's computed archive path must be the real one: " + archive);
            assertTrue(binary.isFile() && binary.length() > 0, "first download must produce a real binary");

            // Simulate the issue: interrupted download, zero-byte archive, no extracted binary.
            deleteRecursively(binary.getParentFile());
            try (var out = new java.io.FileOutputStream(archive)) {
                // truncate to zero bytes
            }
            assertFalse(binary.exists());
            assertTrue(archive.exists() && archive.length() == 0);

            // Prove the bug reproduces: the library treats the zero-byte archive as already
            // downloaded, extracts nothing, and the binary stays missing.
            String pathTheLibraryReturns = downloader.downloadTerraformVersion(VERSION);
            assertFalse(binary.exists(),
                    "reproduction check: main's bug should still leave no binary at " + binary
                            + " (library returned " + pathTheLibraryReturns + ")");

            // Run recovery, then retry the same library call the executor would make.
            TerraformState noopState = mock(TerraformState.class);
            when(noopState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);
            BinaryCacheRecoveryService recovery = new BinaryCacheRecoveryService(noopState, new SimpleMeterRegistry(), locator);

            BinaryCacheRecoveryService.Resolution resolution = recovery.resolve(VERSION, false);
            assertFalse(resolution.alreadyAvailable());
            assertFalse(archive.exists(), "recovery must have deleted the invalid archive");

            downloader.downloadTerraformVersion(VERSION);
            assertTrue(binary.isFile() && binary.length() > 0,
                    "after recovery clears the invalid archive, the library must be able to redownload a real binary");
        } finally {
            if (previousUserHome != null) {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static void deleteRecursively(File file) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Files.delete(file.toPath());
    }
}
