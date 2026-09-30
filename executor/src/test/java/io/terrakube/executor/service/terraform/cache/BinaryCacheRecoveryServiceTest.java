package io.terrakube.executor.service.terraform.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinaryCacheRecoveryServiceTest {

    @TempDir
    Path home;

    private final TerraformState terraformState = mock(TerraformState.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private BinaryCacheRecoveryService subject;
    private BinaryArchiveLocator locator;

    @BeforeEach
    void setUp() {
        locator = new BinaryArchiveLocator(home.toString());
        subject = new BinaryCacheRecoveryService(terraformState, meterRegistry, locator);
    }

    // --- helpers -----------------------------------------------------------------------------

    private File archiveFile(String resolvedVersion, boolean tofu) {
        return locator.locate(resolvedVersion, tofu).file();
    }

    private File binaryFile(String resolvedVersion, boolean tofu) {
        BinaryArchiveLocator.Archive archive = locator.locate(resolvedVersion, tofu);
        return new File(archive.binaryVersionDirectory(), archive.executableName());
    }

    private void writeZeroByteFile(File file) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        Files.createFile(file.toPath());
    }

    private void writeBytes(File file, byte[] bytes) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private void writeValidZip(File file, String entryName, byte[] content) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
    }

    // --- 1. valid local executable bypasses everything ---------------------------------------

    @Test
    void validLocalExecutableBypassesArchiveValidationAndCleanup() throws Exception {
        File binary = binaryFile("1.9.0", false);
        writeBytes(binary, "#!/bin/sh\necho terraform".getBytes());

        BinaryCacheRecoveryService.Resolution resolution = subject.resolve("1.9.0", false);

        assertTrue(resolution.alreadyAvailable());
        assertEquals("existing-local", resolution.source());
        assertEquals(binary, resolution.binaryFile());
        verify(terraformState, never()).downloadTerraformBinary(anyString(), anyBoolean(), any());
        assertEquals(0, meterRegistry.find("terrakube.executor.binary.cache.invalid").counters().size());
    }

    // --- 2. missing binary + zero-byte OpenTofu archive ---------------------------------------

    @Test
    void zeroByteTofuArchiveIsRemovedAndFreshDownloadIsPermitted() throws Exception {
        File archive = archiveFile("v1.13.0", true);
        writeZeroByteFile(archive);
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);

        BinaryCacheRecoveryService.Resolution resolution = subject.resolve("v1.13.0", true);

        assertFalse(resolution.alreadyAvailable());
        assertEquals("local-download", resolution.source());
        assertFalse(archive.exists(), "the zero-byte archive should have been deleted");
        assertEquals(1.0, meterRegistry.get("terrakube.executor.binary.cache.invalid")
                .tag("product", "tofu").tag("reason", "zero_byte").counter().count());
    }

    // --- 3. corrupt / non-ZIP archive -----------------------------------------------------------

    @Test
    void corruptNonZipArchiveIsRemoved() throws Exception {
        File archive = archiveFile("1.9.0", false);
        writeBytes(archive, "this is not a zip file, just garbage bytes".getBytes());
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);

        BinaryCacheRecoveryService.Resolution resolution = subject.resolve("1.9.0", false);

        assertFalse(resolution.alreadyAvailable());
        assertFalse(archive.exists());
        assertEquals(1.0, meterRegistry.get("terrakube.executor.binary.cache.invalid")
                .tag("product", "terraform").tag("reason", "not_zip").counter().count());
    }

    // --- 4. ZIP without the expected executable -------------------------------------------------

    @Test
    void zipWithoutExpectedExecutableEntryIsRemoved() throws Exception {
        File archive = archiveFile("1.9.0", false);
        writeValidZip(archive, "LICENSE.txt", "not the binary".getBytes());
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);

        BinaryCacheRecoveryService.Resolution resolution = subject.resolve("1.9.0", false);

        assertFalse(resolution.alreadyAvailable());
        assertFalse(archive.exists());
        assertEquals(1.0, meterRegistry.get("terrakube.executor.binary.cache.invalid")
                .tag("product", "terraform").tag("reason", "missing_executable").counter().count());
    }

    // --- 5. valid ZIP with a missing binary: existing extraction path, archive kept ------------

    @Test
    void validZipWithMissingBinaryIsLeftForTheExistingExtractionPath() throws Exception {
        File archive = archiveFile("1.9.0", false);
        writeValidZip(archive, "terraform", "#!/bin/sh\necho terraform".getBytes());
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);

        BinaryCacheRecoveryService.Resolution resolution = subject.resolve("1.9.0", false);

        assertFalse(resolution.alreadyAvailable());
        assertEquals("local-download", resolution.source());
        assertTrue(archive.exists(), "a valid archive must not be deleted just because the binary was not extracted yet");
        assertEquals(0, meterRegistry.find("terrakube.executor.binary.cache.invalid").counters().size());
    }

    // --- 6. valid S3-restored binary prevents archive cleanup and remote download ---------------

    @Test
    void validS3RestoredBinaryPreventsArchiveCleanupAndRemoteDownload() throws Exception {
        File archive = archiveFile("1.9.0", false);
        writeZeroByteFile(archive); // deliberately invalid, must be left alone once S3 succeeds
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenAnswer(invocation -> {
            File target = invocation.getArgument(2);
            writeBytes(target, "#!/bin/sh\necho terraform".getBytes());
            return true;
        });

        BinaryCacheRecoveryService.Resolution resolution = subject.resolve("1.9.0", false);

        assertTrue(resolution.alreadyAvailable());
        assertEquals("s3", resolution.source());
        assertTrue(archive.exists(), "S3 restore succeeding must not touch the (unrelated, still invalid) local archive");
        assertEquals(0, meterRegistry.find("terrakube.executor.binary.cache.invalid").counters().size());
    }

    // --- security: a version string that embeds a path traversal is refused, not deleted --------

    @Test
    void aResolvedVersionThatWouldEscapeTheProductDirectoryIsRefusedNotDeleted() throws Exception {
        // resolvedVersion is always a real release version in practice; this proves the guard
        // holds even if that ever stopped being true.
        String maliciousVersion = "../escaped-outside-product-dir";
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);

        // ".." only resolves once "terraform/" itself physically exists to walk back out of.
        Files.createDirectories(locator.locate("1.9.0", false).productDirectory().toPath());
        File binaryDir = locator.locate(maliciousVersion, false).binaryVersionDirectory();
        File binaryFile = new File(binaryDir, "terraform");
        writeZeroByteFile(binaryFile);

        BinaryCacheRecoveryException error = assertThrows(BinaryCacheRecoveryException.class,
                () -> subject.resolve(maliciousVersion, false));

        assertTrue(error.getMessage().contains("Refusing to delete"));
        assertTrue(binaryFile.exists(), "a path-traversal version string must never let recovery delete outside the product cache directory");
    }

    // --- 7. cleanup failure surfaces an actionable error, never a missing-executable path -------

    @Test
    void cleanupFailureRaisesAnActionableCacheRecoveryError() throws Exception {
        File archive = archiveFile("1.9.0", false);
        writeZeroByteFile(archive);
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);
        // Deleting a file needs write+execute on its parent directory; revoke that so the cleanup itself fails.
        File parent = archive.getParentFile();
        assertTrue(parent.setWritable(false));
        try {
            BinaryCacheRecoveryException error = assertThrows(BinaryCacheRecoveryException.class,
                    () -> subject.resolve("1.9.0", false));
            assertTrue(error.getMessage().contains("terraform"));
            assertTrue(error.getMessage().contains("1.9.0"));
        } finally {
            assertTrue(parent.setWritable(true));
        }
    }

    // --- 8. both products, linux/amd64 at minimum (exercised throughout via archiveFile/binaryFile) --

    @Test
    void terraformAndTofuAreBothCoveredEndToEnd() throws Exception {
        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenReturn(false);

        File terraformArchive = archiveFile("1.9.0", false);
        writeZeroByteFile(terraformArchive);
        assertFalse(subject.resolve("1.9.0", false).alreadyAvailable());
        assertFalse(terraformArchive.exists());

        File tofuArchive = archiveFile("v1.13.0", true);
        writeZeroByteFile(tofuArchive);
        assertFalse(subject.resolve("v1.13.0", true).alreadyAvailable());
        assertFalse(tofuArchive.exists());
    }

    // --- 9. concurrent requests for the same product/version are serialised -------------------

    @Test
    void concurrentRequestsForTheSameVersionAreSerialisedByTheLock() throws Exception {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        when(terraformState.downloadTerraformBinary(anyString(), anyBoolean(), any())).thenAnswer(invocation -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
            Thread.sleep(50);
            concurrent.decrementAndGet();
            return false;
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        subject.resolve("1.9.0", false);
                    } catch (Exception ignored) {
                        // archive cleanup races are not what this test is asserting
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, maxConcurrent.get(), "the per-version lock must stop two resolutions of the same version overlapping");
    }
}
