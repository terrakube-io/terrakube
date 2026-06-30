package io.terrakube.api.plugin.storage.local;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalStorageTypeServiceImplTest {

    @TempDir
    Path tempDir;

    private LocalStorageTypeServiceImpl localStorageTypeService = new LocalStorageTypeServiceImpl();

    @Test
    void testUploadTerraformStateJson() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            localStorageTypeService.uploadTerraformStateJson("org1", "ws1", "{}", "hist1");

            mockedFileUtils.verify(() -> FileUtils.writeStringToFile(any(File.class), eq("{}"), anyString()));
        }
    }

    @Test
    void testUploadState() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            localStorageTypeService.uploadState("org1", "ws1", "state", "hist1");

            mockedFileUtils.verify(() -> FileUtils.writeStringToFile(any(File.class), eq("state"), anyString()), times(2));
        }
    }

    @Test
    void testSaveContext() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            localStorageTypeService.saveContext(123, "context");

            mockedFileUtils.verify(() -> FileUtils.writeStringToFile(any(File.class), eq("context"), eq("UTF-8")));
        }
    }

    @Test
    void testGetContext() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            
            // Create the file so it exists
            File tempFile = tempDir.resolve(".terraform-spring-boot/local/output/context/123/context.json").toFile();
            tempFile.getParentFile().mkdirs();
            java.nio.file.Files.writeString(tempFile.toPath(), "{\"a\":1}");

            String result = localStorageTypeService.getContext(123);

            assertEquals("{\"a\":1}", result);
        }
    }

    @Test
    void testCreateContentFile() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            InputStream is = new ByteArrayInputStream("data".getBytes());

            localStorageTypeService.createContentFile("id1", is);

            mockedFileUtils.verify(() -> FileUtils.writeByteArrayToFile(any(File.class), any(byte[].class)));
        }
    }

    @Test
    void testDeleteModuleStorage() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            localStorageTypeService.deleteModuleStorage("org", "mod", "prov");

            mockedFileUtils.verify(() -> FileUtils.cleanDirectory(any(File.class)));
        }
    }

    @Test
    void testMigrateToOrganization() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            boolean result = localStorageTypeService.migrateToOrganization("org1", "ws1", "org2");

            assertTrue(result);
            mockedFileUtils.verify(() -> FileUtils.moveToDirectory(any(File.class), any(File.class), eq(true)), times(3));
        }
    }

    @Test
    void testUploadStepOutput() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            byte[] payload = "step-output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            localStorageTypeService.uploadStepOutput("org1", "job1", "step1", payload);

            mockedFileUtils.verify(() -> FileUtils.forceMkdir(any(File.class)));
            mockedFileUtils.verify(() -> FileUtils.writeByteArrayToFile(any(File.class), eq(payload)));
        }
    }

    @Test
    void testUploadTerraformPlan() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            byte[] plan = new byte[]{1, 2, 3, 4};
            localStorageTypeService.uploadTerraformPlan("org1", "ws1", "job1", "step1", plan);

            mockedFileUtils.verify(() -> FileUtils.forceMkdir(any(File.class)));
            mockedFileUtils.verify(() -> FileUtils.writeByteArrayToFile(any(File.class), eq(plan)));
        }
    }

    @Test
    void testDeleteCurrentTerraformState() throws IOException {
        File stateFile = tempDir.resolve(".terraform-spring-boot/local/backend/org1/ws1/terraform.tfstate").toFile();
        stateFile.getParentFile().mkdirs();
        java.nio.file.Files.writeString(stateFile.toPath(), "{}");

        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            localStorageTypeService.deleteCurrentTerraformState("org1", "ws1");

            assertFalse(stateFile.exists());
        }
    }

    @Test
    void testDeleteCurrentTerraformStateMissingIsNoOp() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            // No file exists — should silently succeed.
            localStorageTypeService.deleteCurrentTerraformState("missing-org", "missing-ws");
        }
    }
}
