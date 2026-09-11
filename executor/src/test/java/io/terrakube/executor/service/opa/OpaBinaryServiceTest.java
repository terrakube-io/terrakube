package io.terrakube.executor.service.opa;

import io.terrakube.executor.plugin.tfstate.TerraformState;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpaBinaryServiceTest {

    private TerraformState terraformState;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private OpaBinaryService opaBinaryService;

    @BeforeEach
    void setUp() {
        terraformState = Mockito.mock(TerraformState.class);
        redisTemplate = Mockito.mock(RedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        opaBinaryService = new OpaBinaryService(terraformState, redisTemplate);
    }

    @AfterEach
    void tearDown() {
        // Clean up any test artifacts under ~/.terrakube/bin/opa/test-version
        File testCacheDir = new File(FileUtils.getUserDirectoryPath(), ".terrakube/bin/opa/test-version");
        FileUtils.deleteQuietly(testCacheDir);
    }

    @Test
    void testResolveArchitecture() {
        String arch = OpaBinaryService.resolveArchitecture();
        assertNotNull(arch);
        assertTrue(arch.equals("amd64") || arch.equals("arm64"));
    }

    @Test
    void testResolveOs() {
        String os = OpaBinaryService.resolveOs();
        assertNotNull(os);
        assertTrue(os.equals("linux") || os.equals("darwin") || os.equals("windows"));
    }

    @Test
    void testGetOpaBinaryFilename() {
        assertEquals("opa_linux_amd64", OpaBinaryService.getOpaBinaryFilename("linux", "amd64"));
        assertEquals("opa_darwin_arm64", OpaBinaryService.getOpaBinaryFilename("darwin", "arm64"));
        assertEquals("opa_windows_amd64.exe", OpaBinaryService.getOpaBinaryFilename("windows", "amd64"));
    }

    @Test
    void testNormalizeAndValidateVersion() {
        // Defaults
        assertEquals("1.20.2", opaBinaryService.normalizeAndValidateVersion(null));
        assertEquals("1.20.2", opaBinaryService.normalizeAndValidateVersion(""));
        assertEquals("1.20.2", opaBinaryService.normalizeAndValidateVersion("   "));

        // Leading 'v' / 'V' stripping and trimming
        assertEquals("1.20.2", opaBinaryService.normalizeAndValidateVersion("v1.20.2"));
        assertEquals("0.68.0", opaBinaryService.normalizeAndValidateVersion("V0.68.0"));
        assertEquals("1.2.3", opaBinaryService.normalizeAndValidateVersion("  v1.2.3  "));
        assertEquals("1.0.0-rc1", opaBinaryService.normalizeAndValidateVersion("1.0.0-rc1"));

        // Custom default in constructor
        OpaBinaryService customDefaultService = new OpaBinaryService(terraformState, redisTemplate, "0.68.0");
        assertEquals("0.68.0", customDefaultService.normalizeAndValidateVersion(null));
        assertEquals("0.68.0", customDefaultService.normalizeAndValidateVersion(""));

        // Invalid versions and path traversal protection
        assertThrows(IllegalArgumentException.class, () -> opaBinaryService.normalizeAndValidateVersion("../../bin"));
        assertThrows(IllegalArgumentException.class, () -> opaBinaryService.normalizeAndValidateVersion("../opa"));
        assertThrows(IllegalArgumentException.class, () -> opaBinaryService.normalizeAndValidateVersion("invalid version"));
        assertThrows(IllegalArgumentException.class, () -> opaBinaryService.normalizeAndValidateVersion("1.0.0; rm -rf /"));
    }

    @Test
    void testGetOpaBinary_FromLocalCache(@TempDir Path tempDir) throws IOException {
        String version = "9.9.9";
        String os = OpaBinaryService.resolveOs();
        String arch = OpaBinaryService.resolveArchitecture();
        String binaryFileName = "windows".equals(os) ? "opa.exe" : "opa";

        File localCacheFile = new File(
                FileUtils.getUserDirectoryPath(),
                String.format(".terrakube/bin/opa/%s/%s_%s/%s", version, os, arch, binaryFileName)
        );
        FileUtils.forceMkdirParent(localCacheFile);
        FileUtils.writeStringToFile(localCacheFile, "cached-opa", StandardCharsets.UTF_8);
        localCacheFile.setExecutable(true, true);

        File resolvedBinary = opaBinaryService.getOpaBinary("v" + version);

        assertNotNull(resolvedBinary);
        assertTrue(resolvedBinary.exists());
        assertEquals(localCacheFile.getAbsolutePath(), resolvedBinary.getAbsolutePath());
        // Cloud download should not have been invoked
        verify(terraformState, never()).downloadOpaBinary(anyString(), anyString(), anyString(), any(File.class));

        FileUtils.deleteQuietly(localCacheFile.getParentFile().getParentFile());
    }

    @Test
    void testGetOpaBinary_FromCloudStorage() throws IOException {
        String version = "9.9.8";
        String os = OpaBinaryService.resolveOs();
        String arch = OpaBinaryService.resolveArchitecture();

        when(terraformState.downloadOpaBinary(eq(version), eq(os), eq(arch), any(File.class)))
                .thenAnswer(invocation -> {
                    File target = invocation.getArgument(3);
                    FileUtils.writeStringToFile(target, "cloud-opa", StandardCharsets.UTF_8);
                    return true;
                });

        File resolvedBinary = opaBinaryService.getOpaBinary("v" + version);

        assertNotNull(resolvedBinary);
        assertTrue(resolvedBinary.exists());
        verify(terraformState, times(1)).downloadOpaBinary(eq(version), eq(os), eq(arch), any(File.class));

        FileUtils.deleteQuietly(resolvedBinary.getParentFile().getParentFile());
    }

    @Test
    void testUploadOpaBinaryWithLock_AcquiredLock() {
        when(valueOperations.setIfAbsent(startsWith("opa-binary-uploading:"), eq("locked"), any(Duration.class)))
                .thenReturn(true);

        when(terraformState.downloadOpaBinary(anyString(), anyString(), anyString(), any(File.class)))
                .thenReturn(false);

        when(terraformState.downloadOpaBinary(eq("9.9.7"), anyString(), anyString(), any(File.class)))
                .thenAnswer(inv -> {
                    File f = inv.getArgument(3);
                    FileUtils.writeStringToFile(f, "data", StandardCharsets.UTF_8);
                    return true;
                });

        File resolved = opaBinaryService.getOpaBinary("9.9.7");
        assertNotNull(resolved);
        FileUtils.deleteQuietly(resolved.getParentFile().getParentFile());
    }
}
