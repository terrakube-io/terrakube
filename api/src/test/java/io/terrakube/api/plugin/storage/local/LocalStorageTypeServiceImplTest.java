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
import java.nio.charset.StandardCharsets;
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

    private LocalStorageTypeServiceImpl localStorageTypeService = new LocalStorageTypeServiceImpl(
            new io.terrakube.api.plugin.storage.StorageRetryMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

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

            mockedFileUtils.verify(() -> FileUtils.writeByteArrayToFile(any(File.class), eq("{}".getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    void testUploadState() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            localStorageTypeService.uploadState("org1", "ws1", "state", "hist1");

            mockedFileUtils.verify(() -> FileUtils.writeByteArrayToFile(any(File.class), eq("state".getBytes(StandardCharsets.UTF_8))), times(2));
        }
    }

    @Test
    void testSaveContext() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            localStorageTypeService.saveContext(123, "context");

            mockedFileUtils.verify(() -> FileUtils.writeByteArrayToFile(any(File.class), eq("context".getBytes(StandardCharsets.UTF_8))));
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
    void getContextReturnsEmptyJsonOnNotFound() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            String result = localStorageTypeService.getContext(999);

            assertEquals("{}", result);
        }
    }

    @Test
    void readThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            // A directory where a file is expected: File.exists() is true (the not-found signal
            // stays unchanged), but opening it for reading fails with a genuine IOException.
            File directoryInsteadOfFile = tempDir.resolve(".terraform-spring-boot/local/output/context/999/context.json").toFile();
            directoryInsteadOfFile.mkdirs();

            assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                    () -> localStorageTypeService.getContext(999));
        }
    }

    @Test
    void writeThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            mockedFileUtils.when(() -> FileUtils.writeByteArrayToFile(any(File.class), any(byte[].class)))
                    .thenThrow(new IOException("disk full"));

            assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                    () -> localStorageTypeService.saveContext(123, "context"));

            mockedFileUtils.verify(() -> FileUtils.writeByteArrayToFile(any(File.class), any(byte[].class)), times(3));
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
    void testPolicyEvaluationLifecycle() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            String uri = "policy-evaluations/123/violations.json";
            String json = "{\"violations\":[]}";

            localStorageTypeService.uploadPolicyEvaluation(uri, json);
            String retrieved = localStorageTypeService.getPolicyEvaluation(uri);
            assertEquals(json, retrieved);

            localStorageTypeService.deletePolicyEvaluation(uri);
            String afterDelete = localStorageTypeService.getPolicyEvaluation(uri);
            assertEquals("{}", afterDelete);
        }
    }

    @Test
    void testSpooledContextPatchLifecycle() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            String orgId = "org1";
            String jobId = "job1";
            String stepId = "step1";
            String phase = "plan";
            byte[] data = "dummy-gzipped-bytes".getBytes(StandardCharsets.UTF_8);

            // 1. Initially absent
            assertFalse(localStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));
            assertNull(localStorageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase));

            // Write test file directly
            File spooledFile = tempDir.resolve(".terraform-spring-boot/local/context-spool/org1/job1/step1-plan.json.gz").toFile();
            spooledFile.getParentFile().mkdirs();
            java.nio.file.Files.write(spooledFile.toPath(), data);

            // 2. Has & Get
            assertTrue(localStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));
            byte[] retrieved = localStorageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase);
            assertArrayEquals(data, retrieved);

            // 3. Delete
            localStorageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
            assertFalse(localStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));
            assertNull(localStorageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase));
    }
    }

    // ----- plan artifacts, with the home directory pointed at a temp directory

    private String originalHome;

    private File bundle(String organizationId, String workspaceId, String jobId, String stepId) throws IOException {
        File file = new File(tempDir.toFile(), ".terraform-spring-boot/local/plan-artifacts/" + organizationId + "/" + workspaceId + "/" + jobId + "/" + stepId + "/plan-artifacts.tar.gz");
        file.getParentFile().mkdirs();
        java.nio.file.Files.writeString(file.toPath(), "bundle");
        return file;
    }

    private void withHome(Runnable test) {
        String original = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            test.run();
        } finally {
            System.setProperty("user.home", original);
        }
    }

    @Test
    void testSpooledContextPatchPathTraversalRejected() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            assertThrows(SecurityException.class, () ->
                    localStorageTypeService.getSpooledContextPatch("../../etc", "job", "step", "plan"));
        }
    }

    @Test
    void testDeleteWorkspaceOutputDataCleansSpooledFolder() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            File spoolFile = tempDir.resolve(".terraform-spring-boot/local/context-spool/org1/100/step1-plan.json.gz").toFile();
            spoolFile.getParentFile().mkdirs();
            java.nio.file.Files.write(spoolFile.toPath(), "test".getBytes(StandardCharsets.UTF_8));
            assertTrue(spoolFile.exists());

            localStorageTypeService.deleteWorkspaceOutputData("org1", java.util.List.of(100));

            assertFalse(spoolFile.exists());
        }
    }

    @Test
    void deletingAJobsArtifactsLeavesOtherJobsAndWorkspacesAlone() throws IOException {
        File mine = bundle("org1", "ws1", "12", "a");
        File sameWorkspaceOtherJob = bundle("org1", "ws1", "120", "a");
        File otherWorkspace = bundle("org1", "ws2", "12", "a");

        withHome(() -> localStorageTypeService.deleteJobArtifacts("org1", "ws1", 12));

        assertFalse(mine.exists());
        assertFalse(mine.getParentFile().getParentFile().exists(), "the job's directory is removed");
        assertTrue(sameWorkspaceOtherJob.exists(), "job 120 is not job 12");
        assertTrue(otherWorkspace.exists());
    }

    @Test
    void deletingAJobsArtifactsWhenThereAreNoneIsHarmless() {
        assertDoesNotThrow(() -> withHome(() -> localStorageTypeService.deleteJobArtifacts("org1", "ws1", 99)));
    }

    @Test
    void deletingAWorkspaceRemovesItsArtifactsEvenWhenItHasNoStateYet() throws IOException {
        File first = bundle("org1", "ws1", "1", "a");
        File second = bundle("org1", "ws1", "2", "a");
        File otherWorkspace = bundle("org1", "ws2", "1", "a");

        withHome(() -> localStorageTypeService.deleteWorkspaceStateData("org1", "ws1"));

        assertFalse(first.exists());
        assertFalse(second.exists());
        assertTrue(otherWorkspace.exists());
    }

    private void writePart(String name, String content) throws IOException {
        File file = tempDir.resolve(".terraform-spring-boot/local/output/o/j/s.parts/" + name).toFile();
        file.getParentFile().mkdirs();
        java.nio.file.Files.writeString(file.toPath(), content);
    }

    @Test
    void listsTheOutputPartsOfAStepWithTheirSizes() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            writePart("000001.part", "hello ");
            writePart("000002.part", "world!");

            var parts = localStorageTypeService.listStepOutputParts("o", "j", "s");

            assertEquals(java.util.Set.of(
                    new io.terrakube.api.plugin.storage.model.StepOutputPart("000001.part", 6),
                    new io.terrakube.api.plugin.storage.model.StepOutputPart("000002.part", 6)), new java.util.HashSet<>(parts));
        }
    }

    @Test
    void aStepWithoutAPartsFolderHasNoParts() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            assertTrue(localStorageTypeService.listStepOutputParts("o", "j", "s").isEmpty());
        }
    }

    @Test
    void opensARangeOfOneOutputPart() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            writePart("000001.part", "0123456789");

            try (InputStream in = localStorageTypeService.openStepOutputPart("o", "j", "s", "000001.part", 3, 4)) {
                assertEquals("3456", new String(IOUtils.toByteArray(in)));
            }
        }
    }

    @Test
    void refusesAPartNameThatIsNotAPart() {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());

            assertThrows(IOException.class, () -> localStorageTypeService.openStepOutputPart("o", "j", "s", "../s.tfoutput", 0, 4));
        }
    }

    @Test
    void deletingTheOutputOfAJobAlsoRemovesTheParts() throws IOException {
        try (MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class, CALLS_REAL_METHODS)) {
            mockedFileUtils.when(FileUtils::getUserDirectoryPath).thenReturn(tempDir.toString());
            File part = tempDir.resolve(".terraform-spring-boot/local/output/org1/100/step1.parts/000001.part").toFile();
            part.getParentFile().mkdirs();
            java.nio.file.Files.writeString(part.toPath(), "abc");
            File finalOutput = tempDir.resolve(".terraform-spring-boot/local/output/org1/100/step1.tfoutput").toFile();
            java.nio.file.Files.writeString(finalOutput.toPath(), "abc");
            File otherJob = tempDir.resolve(".terraform-spring-boot/local/output/org1/101/step1.parts/000001.part").toFile();
            otherJob.getParentFile().mkdirs();
            java.nio.file.Files.writeString(otherJob.toPath(), "abc");

            localStorageTypeService.deleteWorkspaceOutputData("org1", java.util.List.of(100));

            assertFalse(part.exists());
            assertFalse(finalOutput.exists());
            assertFalse(part.getParentFile().exists());
            assertTrue(otherJob.exists());
        }
    }
}
