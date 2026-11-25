package io.terrakube.executor.service.infracost;

import com.sun.net.httpserver.HttpServer;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InfracostBinaryServiceTest {

    @TempDir
    Path tempDir;

    private TerraformState terraformState;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private HttpServer httpServer;
    private String baseUrl;
    private InfracostBinaryService service;

    @BeforeEach
    void setUp() throws IOException {
        terraformState = Mockito.mock(TerraformState.class);
        redisTemplate = Mockito.mock(RedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("infracost-binary-uploading:"), eq("locked"), any(Duration.class)))
                .thenReturn(true);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.start();
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();

        service = new InfracostBinaryService(
                terraformState,
                redisTemplate,
                InfracostBinaryService.DEFAULT_INFRACOST_VERSION,
                tempDir.toString(),
                baseUrl,
                name -> null
        );
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void testNormalizeAndValidateVersion() {
        assertEquals("0.10.50", service.normalizeAndValidateVersion(null));
        assertEquals("0.10.50", service.normalizeAndValidateVersion("  "));
        assertEquals("0.10.50", service.normalizeAndValidateVersion("v0.10.50"));
        assertEquals("0.10.42", service.normalizeAndValidateVersion("V0.10.42"));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeAndValidateVersion("../../bin"));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeAndValidateVersion("0.10.50; rm -rf /"));
    }

    @Test
    void testGetInfracostBinary_FromPathWhenVersionUnset() throws IOException {
        File pathBin = tempDir.resolve("path-infracost").toFile();
        Files.writeString(pathBin.toPath(), "#!/bin/sh\necho ok\n");
        assertTrue(pathBin.setExecutable(true, true));

        InfracostBinaryService pathAware = new InfracostBinaryService(
                terraformState,
                redisTemplate,
                "0.10.50",
                tempDir.toString(),
                baseUrl,
                name -> pathBin,
                file -> Optional.of("0.10.50")
        );

        File resolved = pathAware.getInfracostBinary(null);
        assertEquals(pathBin.getAbsolutePath(), resolved.getAbsolutePath());
        verifyNoInteractions(terraformState);
    }

    @Test
    void testGetInfracostBinary_PathVersionMismatchSkipsPathAndUsesManagedCache() throws Exception {
        File pathBin = tempDir.resolve("path-infracost-old").toFile();
        Files.writeString(pathBin.toPath(), "#!/bin/sh\necho 'Infracost v0.10.42'\n");
        assertTrue(pathBin.setExecutable(true, true));

        String os = InfracostBinaryService.resolveOs();
        String arch = InfracostBinaryService.resolveArchitecture();
        byte[] freshBinary = "#!/bin/sh\necho managed-0.10.50\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger downloads = registerTarballEndpoints("0.10.50", os, arch, "infracost", freshBinary, null);
        when(terraformState.downloadInfracostBinary(eq("0.10.50"), eq(os), eq(arch), any())).thenReturn(false);

        InfracostBinaryService pathGuarded = new InfracostBinaryService(
                terraformState,
                redisTemplate,
                "0.10.50",
                tempDir.toString(),
                baseUrl,
                name -> pathBin,
                file -> Optional.of("0.10.42")
        );

        File resolved = pathGuarded.getInfracostBinary(null);
        assertNotEquals(pathBin.getAbsolutePath(), resolved.getAbsolutePath());
        assertEquals(1, downloads.get());
        assertArrayEquals(freshBinary, Files.readAllBytes(resolved.toPath()));
    }

    @Test
    void testGetInfracostBinary_FromLocalCacheWhenSha256MatchesAndMemoizesHash() throws Exception {
        String version = "0.10.50";
        String os = InfracostBinaryService.resolveOs();
        String arch = InfracostBinaryService.resolveArchitecture();
        String binName = "windows".equals(os) ? "infracost.exe" : "infracost";

        Path cacheDir = tempDir.resolve(String.format(".terrakube/bin/infracost/%s/%s_%s", version, os, arch));
        Files.createDirectories(cacheDir);
        File cachedBin = cacheDir.resolve(binName).toFile();
        byte[] content = "#!/bin/sh\necho cached\n".getBytes(StandardCharsets.UTF_8);
        Files.write(cachedBin.toPath(), content);
        assertTrue(cachedBin.setExecutable(true, true));
        Files.writeString(cacheDir.resolve("infracost.sha256"), sha256Hex(content));

        File resolved = service.getInfracostBinary("v" + version);
        assertEquals(cachedBin.getAbsolutePath(), resolved.getAbsolutePath());

        // Second call hits memoized (mtime, size) hash entry
        File secondResolved = service.getInfracostBinary("v" + version);
        assertEquals(cachedBin.getAbsolutePath(), secondResolved.getAbsolutePath());
        verify(terraformState, never()).downloadInfracostBinary(anyString(), anyString(), anyString(), any());

        // Modifying cached binary (different size/mtime) invalidates memoized entry and triggers self-healing
        byte[] freshBinary = "#!/bin/sh\necho self-healed-v0.10.50\n".getBytes(StandardCharsets.UTF_8);
        Files.writeString(cachedBin.toPath(), "tampered-longer-binary-content");
        AtomicInteger downloads = registerTarballEndpoints(version, os, arch, binName, freshBinary, null);
        File repaired = service.getInfracostBinary("v" + version);
        assertEquals(1, downloads.get());
        assertArrayEquals(freshBinary, Files.readAllBytes(repaired.toPath()));
    }

    @Test
    void testGetInfracostBinary_LocalCacheSha256Mismatch_DeletesAndRedownloadsLocally() throws Exception {
        String version = "0.10.50";
        String os = InfracostBinaryService.resolveOs();
        String arch = InfracostBinaryService.resolveArchitecture();
        String binName = "windows".equals(os) ? "infracost.exe" : "infracost";

        Path cacheDir = tempDir.resolve(String.format(".terrakube/bin/infracost/%s/%s_%s", version, os, arch));
        Files.createDirectories(cacheDir);
        File staleBin = cacheDir.resolve(binName).toFile();
        Files.writeString(staleBin.toPath(), "corrupted-local-binary");
        assertTrue(staleBin.setExecutable(true, true));
        Files.writeString(cacheDir.resolve("infracost.sha256"), "0000000000000000000000000000000000000000000000000000000000000000");

        byte[] freshBinary = "#!/bin/sh\necho fresh-upstream\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger downloadCount = registerTarballEndpoints(version, os, arch, "infracost-" + os + "-" + arch, freshBinary, null);

        when(terraformState.downloadInfracostBinary(eq(version), eq(os), eq(arch), any())).thenReturn(false);
        when(terraformState.saveInfracostBinary(eq(version), eq(os), eq(arch), any())).thenReturn(true);
        when(terraformState.saveInfracostChecksum(eq(version), eq(os), eq(arch), eq(sha256Hex(freshBinary)))).thenReturn(true);

        File resolved = service.getInfracostBinary(version);

        assertEquals(1, downloadCount.get());
        assertArrayEquals(freshBinary, Files.readAllBytes(resolved.toPath()));
        assertEquals(sha256Hex(freshBinary), Files.readString(cacheDir.resolve("infracost.sha256")).trim());
        verify(terraformState).saveInfracostBinary(eq(version), eq(os), eq(arch), eq(resolved));
        verify(terraformState).saveInfracostChecksum(eq(version), eq(os), eq(arch), eq(sha256Hex(freshBinary)));
    }

    @Test
    void testGetInfracostBinary_StorageCacheSha256Valid() throws Exception {
        String version = "0.10.49";
        String os = InfracostBinaryService.resolveOs();
        String arch = InfracostBinaryService.resolveArchitecture();
        byte[] storedBinary = "#!/bin/sh\necho from-s3\n".getBytes(StandardCharsets.UTF_8);
        String expectedSha256 = sha256Hex(storedBinary);

        when(terraformState.downloadInfracostBinary(eq(version), eq(os), eq(arch), any()))
                .thenAnswer(inv -> {
                    File target = inv.getArgument(3);
                    FileUtils.writeByteArrayToFile(target, storedBinary);
                    return true;
                });
        when(terraformState.downloadInfracostChecksum(eq(version), eq(os), eq(arch)))
                .thenReturn(Optional.of(expectedSha256));

        File resolved = service.getInfracostBinary(version);

        assertArrayEquals(storedBinary, Files.readAllBytes(resolved.toPath()));
        assertTrue(resolved.canExecute());
        File sidecar = new File(resolved.getParentFile(), "infracost.sha256");
        assertTrue(sidecar.isFile());
        assertEquals(expectedSha256, Files.readString(sidecar.toPath()).trim());
    }

    @Test
    void testGetInfracostBinary_StorageCacheSha256Mismatch_DeletesCachedBinaryAndDownloadsFresh() throws Exception {
        String version = "0.10.48";
        String os = InfracostBinaryService.resolveOs();
        String arch = InfracostBinaryService.resolveArchitecture();
        byte[] corruptStorageBinary = "corrupted-storage-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] freshBinary = "#!/bin/sh\necho fresh-after-mismatch\n".getBytes(StandardCharsets.UTF_8);
        String freshSha256 = sha256Hex(freshBinary);

        when(terraformState.downloadInfracostBinary(eq(version), eq(os), eq(arch), any()))
                .thenAnswer(inv -> {
                    File target = inv.getArgument(3);
                    FileUtils.writeByteArrayToFile(target, corruptStorageBinary);
                    return true;
                });
        when(terraformState.downloadInfracostChecksum(eq(version), eq(os), eq(arch)))
                .thenReturn(Optional.of("1111111111111111111111111111111111111111111111111111111111111111"));

        AtomicInteger downloadCount = registerTarballEndpoints(version, os, arch, "infracost", freshBinary, null);
        when(terraformState.saveInfracostBinary(eq(version), eq(os), eq(arch), any())).thenReturn(true);
        when(terraformState.saveInfracostChecksum(eq(version), eq(os), eq(arch), eq(freshSha256))).thenReturn(true);

        File resolved = service.getInfracostBinary(version);

        assertEquals(1, downloadCount.get());
        assertArrayEquals(freshBinary, Files.readAllBytes(resolved.toPath()));
        assertEquals(freshSha256, Files.readString(new File(resolved.getParentFile(), "infracost.sha256").toPath()).trim());
        verify(terraformState).saveInfracostBinary(eq(version), eq(os), eq(arch), eq(resolved));
        verify(terraformState).saveInfracostChecksum(eq(version), eq(os), eq(arch), eq(freshSha256));
    }

    @Test
    void testGetInfracostBinary_DeadlineExceededOnSlowDownloadFailsFast() throws Exception {
        String version = "0.10.46";
        String os = InfracostBinaryService.resolveOs();
        String arch = InfracostBinaryService.resolveArchitecture();
        String archiveName = InfracostBinaryService.getArchiveFilename(os, arch);
        httpServer.createContext("/v" + version + "/" + archiveName, exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        when(terraformState.downloadInfracostBinary(eq(version), eq(os), eq(arch), any())).thenReturn(false);

        Instant shortDeadline = Instant.now().plusMillis(250);
        assertThrows(IllegalStateException.class, () -> service.getInfracostBinary(version, shortDeadline));
    }

    @Test
    void testGetInfracostBinary_UpstreamSha256Mismatch_FailsAndCleansCache() throws Exception {
        String version = "0.10.47";
        String os = InfracostBinaryService.resolveOs();
        String arch = InfracostBinaryService.resolveArchitecture();
        byte[] freshBinary = "#!/bin/sh\necho tampered\n".getBytes(StandardCharsets.UTF_8);

        registerTarballEndpoints(
                version, os, arch, "infracost", freshBinary,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        when(terraformState.downloadInfracostBinary(eq(version), eq(os), eq(arch), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.getInfracostBinary(version));

        Path binPath = tempDir.resolve(String.format(".terrakube/bin/infracost/%s/%s_%s/infracost", version, os, arch));
        assertFalse(Files.exists(binPath));
        verify(terraformState, never()).saveInfracostBinary(anyString(), anyString(), anyString(), any());
    }

    private AtomicInteger registerTarballEndpoints(
            String version,
            String os,
            String arch,
            String entryName,
            byte[] binaryBytes,
            String overrideSha256) throws Exception {
        byte[] tarGzBytes = buildTarGz(entryName, binaryBytes);
        String archiveName = InfracostBinaryService.getArchiveFilename(os, arch);
        String sha256 = overrideSha256 != null ? overrideSha256 : sha256Hex(tarGzBytes);
        String sha256Body = sha256 + "  " + archiveName + "\n";

        AtomicInteger hits = new AtomicInteger();
        String prefix = "/v" + version + "/" + archiveName;
        httpServer.createContext(prefix, exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, tarGzBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(tarGzBytes);
            }
        });
        httpServer.createContext(prefix + ".sha256", exchange -> {
            byte[] bytes = sha256Body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        return hits;
    }

    private static byte[] buildTarGz(String entryName, byte[] contents) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(contents.length);
            entry.setMode(0755);
            tarOut.putArchiveEntry(entry);
            tarOut.write(contents);
            tarOut.closeArchiveEntry();
        }
        return baos.toByteArray();
    }

    private static String sha1Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
