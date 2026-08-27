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

import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import org.apache.commons.io.IOUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalStorageTypeServiceImplTest {

    @TempDir
    Path tempDir;

    private LocalStorageTypeServiceImpl localStorageTypeService = new LocalStorageTypeServiceImpl();

    private File writeStepOutput(String content) throws IOException {
        File file = tempDir.resolve(".terraform-spring-boot/local/output/o/j/s.tfoutput").toFile();
        file.getParentFile().mkdirs();
        java.nio.file.Files.writeString(file.toPath(), content);
        return file;
    }

    @Test
    void getStepOutputStreamReturnsWholeFileWhenNoRange() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            writeStepOutput("hello world");

            try (StepOutputStream result = localStorageTypeService.getStepOutputStream("o", "j", "s", null)) {
                assertTrue(result.isExists());
                assertFalse(result.isPartial());
                assertEquals(11L, result.getContentLength());
                assertEquals("hello world", new String(IOUtils.toByteArray(result.getContent())));
            }
        }
    }

    @Test
    void getStepOutputStreamTailsWithSuffixRange() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            writeStepOutput("0123456789");

            try (StepOutputStream result = localStorageTypeService.getStepOutputStream(
                    "o", "j", "s", ByteRange.parse("bytes=-4").orElseThrow())) {
                assertTrue(result.isPartial());
                assertEquals("bytes 6-9/10", result.getContentRange());
                assertEquals(10L, result.getTotalLength());
                assertEquals("6789", new String(IOUtils.toByteArray(result.getContent())));
            }
        }
    }

    @Test
    void getStepOutputStreamReturnsMissingWhenFileAbsent() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            StepOutputStream result = localStorageTypeService.getStepOutputStream("o", "j", "s", null);

            assertFalse(result.isExists());
        }
    }

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
}
