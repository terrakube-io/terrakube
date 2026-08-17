package io.terrakube.storage.plugin.local;

import io.terrakube.storage.plugin.core.GitService;
import io.terrakube.storage.plugin.core.ModuleVersionDownload;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            fileUtilsMockedStatic.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toAbsolutePath().toString());
            fileUtilsMockedStatic.when(() -> FileUtils.forceMkdirParent(any(File.class))).thenAnswer(invocation -> {
                File file = invocation.getArgument(0);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                return null;
            });
            fileUtilsMockedStatic.when(() -> FileUtils.cleanDirectory(any(File.class))).thenAnswer(invocation -> {
                return null;
            });

            File gitCloneDir = tempDir.resolve("git-clone").toFile();
            assertTrue(gitCloneDir.mkdirs());
            File dummyFile = new File(gitCloneDir, "main.tf");
            java.nio.file.Files.write(dummyFile.toPath(), "resource \"null_resource\" \"this\" {}".getBytes(StandardCharsets.UTF_8));

            when(gitService.getCloneRepositoryByTag(any(ModuleVersionDownload.class)))
                    .thenReturn(gitCloneDir);

            ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                    "vcsConn", "token", "tag", "folder");
            String result = localStorageService.searchModule("org", "module", "local", download);

            assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/local/1.0.0/module.zip", result);
            
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
    void shouldReturnEmptyBytesWhenDownloadFails() throws IOException {
        LocalStorageServiceImpl localStorageService = LocalStorageServiceImpl.builder()
                .gitService(mock(GitService.class))
                .registryHostname("host")
                .build();

        try (MockedStatic<FileUtils> fileUtilsMockedStatic = Mockito.mockStatic(FileUtils.class)) {
            fileUtilsMockedStatic.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toAbsolutePath().toString());

            byte[] result = localStorageService.downloadModule("org", "module", "local", "non-existent");

            assertEquals(0, result.length);
        }
    }
}
