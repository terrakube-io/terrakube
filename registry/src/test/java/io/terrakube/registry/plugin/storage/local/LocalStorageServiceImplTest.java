package io.terrakube.registry.plugin.storage.local;

import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalStorageServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSearchModuleAndZipIfNotExist() throws IOException {
        GitService gitService = mock(GitService.class);
        String registryHostname = "https://registry.terrakube.io";

        LocalStorageServiceImpl localStorageService = LocalStorageServiceImpl.builder()
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        try (MockedStatic<FileUtils> fileUtilsMockedStatic = Mockito.mockStatic(FileUtils.class)) {
            // Mock getUserDirectoryPath to use our tempDir
            fileUtilsMockedStatic.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toAbsolutePath().toString());
            // Need to allow other FileUtils methods to work normally
            fileUtilsMockedStatic.when(() -> FileUtils.forceMkdirParent(any(File.class))).thenAnswer(invocation -> {
                File file = invocation.getArgument(0);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                return null;
            });

            File moduleZip = tempDir.resolve("module.zip").toFile();
            java.nio.file.Files.writeString(moduleZip.toPath(), "zip");
            doAnswer(invocation -> {
                invocation.<GitService.ModuleZipHandler>getArgument(1).accept(moduleZip);
                return null;
            }).when(gitService).withModuleZip(any(ModuleVersionDownload.class), any());

            ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                    "vcsConn", "token", "tag", "folder");
            String result = localStorageService.searchModule("org", "module", "local", download);

            assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/local/1.0.0/module.zip", result);
            
            // Verify zip file was created
            File expectedZip = new File(tempDir.toFile(), "/.terraform-spring-boot/local/modules/org/module/local/1.0.0/module.zip");
            assertTrue(expectedZip.exists());
        }
    }

    @Test
    void shouldDownloadModule() throws IOException {
        GitService gitService = mock(GitService.class);
        String registryHostname = "https://registry.terrakube.io";

        LocalStorageServiceImpl localStorageService = LocalStorageServiceImpl.builder()
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        try (MockedStatic<FileUtils> fileUtilsMockedStatic = Mockito.mockStatic(FileUtils.class)) {
            fileUtilsMockedStatic.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toAbsolutePath().toString());

            // Create a dummy zip file
            File moduleDir = tempDir.resolve(".terraform-spring-boot/local/modules/org/module/local/1.0.0").toFile();
            assertTrue(moduleDir.mkdirs());
            File zipFile = new File(moduleDir, "module.zip");
            byte[] expectedData = "zip-content".getBytes();
            java.nio.file.Files.write(zipFile.toPath(), expectedData);

            byte[] result = localStorageService.downloadModule("org", "module", "local", "1.0.0");

            assertArrayEquals(expectedData, result);
        }
    }

    @Test
    void shouldThrowWhenDownloadFails() throws IOException {
        LocalStorageServiceImpl localStorageService = LocalStorageServiceImpl.builder()
                .gitService(mock(GitService.class))
                .registryHostname("host")
                .build();

        try (MockedStatic<FileUtils> fileUtilsMockedStatic = Mockito.mockStatic(FileUtils.class)) {
            fileUtilsMockedStatic.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toAbsolutePath().toString());

            assertThrows(StorageUnavailableException.class,
                    () -> localStorageService.downloadModule("org", "module", "local", "non-existent"));
        }
    }

    @Test
    void objectRoundTrip() throws IOException {
        LocalStorageServiceImpl storage = LocalStorageServiceImpl.builder()
                .gitService(mock(GitService.class)).registryHostname("host").build();
        Path source = tempDir.resolve("source.zip");
        java.nio.file.Files.writeString(source, "archive");

        try (MockedStatic<FileUtils> fileUtilsMockedStatic = Mockito.mockStatic(FileUtils.class)) {
            fileUtilsMockedStatic.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toAbsolutePath().toString());
            String key = "mirror/registry.terraform.io/hashicorp/random/index.json";

            assertFalse(storage.objectExists(key));
            assertTrue(storage.readObject(key).isEmpty());
            storage.writeObject(key, source, "application/json");
            storage.writeObject(key, source, "application/json");

            assertTrue(storage.objectExists(key));
            try (java.io.InputStream in = storage.readObject(key).orElseThrow()) {
                assertEquals("archive", new String(in.readAllBytes()));
            }
            assertTrue(java.nio.file.Files.isRegularFile(
                    tempDir.resolve(".terraform-spring-boot/local/mirror/registry.terraform.io/hashicorp/random/index.json")));
            assertTrue(storage.presignObject(key).isEmpty());
        }
    }

    @Test
    void keyEscapingBaseIsRejected() {
        LocalStorageServiceImpl storage = LocalStorageServiceImpl.builder()
                .gitService(mock(GitService.class)).registryHostname("host").build();

        try (MockedStatic<FileUtils> fileUtilsMockedStatic = Mockito.mockStatic(FileUtils.class)) {
            fileUtilsMockedStatic.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toAbsolutePath().toString());

            assertThrows(IllegalArgumentException.class, () -> storage.readObject("mirror/../../outside.json"));
            assertThrows(IllegalArgumentException.class, () -> storage.objectExists("/etc/passwd"));
            assertThrows(IllegalArgumentException.class, () -> storage.writeObject("..", tempDir, "x"));
        }
    }
}
