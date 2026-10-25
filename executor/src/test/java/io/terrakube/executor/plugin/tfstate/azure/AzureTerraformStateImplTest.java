package io.terrakube.executor.plugin.tfstate.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AzureTerraformStateImplTest {

    private BlobServiceClient blobServiceClient;
    private BlobContainerClient blobContainerClient;
    private BlobClient blobClient;
    private TerrakubeClient terrakubeClient;
    private TerraformOutputPathService terraformOutputPathService;
    private TerraformStatePathService terraformStatePathService;
    private AzureTerraformStateImpl azureTerraformState;

    private final String resourceGroupName = "test-rg";
    private final String storageAccountName = "test-account";
    private final String storageContainerName = "test-container";
    private final String storageAccessKey = "test-key";

    @BeforeEach
    void setUp() {
        blobServiceClient = mock(BlobServiceClient.class);
        blobContainerClient = mock(BlobContainerClient.class);
        blobClient = mock(BlobClient.class);
        terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        terraformOutputPathService = mock(TerraformOutputPathService.class);
        terraformStatePathService = mock(TerraformStatePathService.class);

        when(blobServiceClient.getBlobContainerClient(anyString())).thenReturn(blobContainerClient);
        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);

        azureTerraformState = AzureTerraformStateImpl.builder()
                .resourceGroupName(resourceGroupName)
                .storageAccountName(storageAccountName)
                .storageContainerName(storageContainerName)
                .storageAccessKey(storageAccessKey)
                .blobServiceClient(blobServiceClient)
                .terrakubeClient(terrakubeClient)
                .terraformOutputPathService(terraformOutputPathService)
                .terraformStatePathService(terraformStatePathService)
                .storageRetryMetrics(new io.terrakube.executor.plugin.tfstate.StorageRetryMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .build();
    }

    @Test
    void testGetBackendStateFile(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String terraformVersion = "1.5.0";
        File workingDirectory = tempDir.toFile();

        String result = azureTerraformState.getBackendStateFile(organizationId, workspaceId, workingDirectory, terraformVersion);

        assertEquals("azure_backend_override.tf", result);
        File backendFile = new File(workingDirectory, "azure_backend_override.tf");
        assertTrue(backendFile.exists());
        String content = FileUtils.readFileToString(backendFile, Charset.defaultCharset());
        assertTrue(content.contains("resource_group_name  = \"test-rg\""));
        assertTrue(content.contains("storage_account_name = \"test-account\""));
        assertTrue(content.contains("container_name       = \"test-container\""));
        assertTrue(content.contains("key                  = \"org1/ws1/terraform.tfstate\""));
        assertTrue(content.contains("access_key           = \"test-key\""));
    }

    @Test
    void testSaveTerraformPlan(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();
        File planFile = new File(workingDirectory, "terraformLibrary.tfPlan");
        FileUtils.writeStringToFile(planFile, "plan-content", Charset.defaultCharset());

        when(blobContainerClient.exists()).thenReturn(true);
        when(blobClient.getBlobUrl()).thenReturn("https://storage.azure.com/plan");

        String result = azureTerraformState.saveTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertEquals("https://storage.azure.com/plan", result);
        verify(blobClient).uploadFromFile(planFile.getAbsolutePath(), true);
    }

    @Test
    void testSaveTerraformPlan_ContainerCreated(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();
        File planFile = new File(workingDirectory, "terraformLibrary.tfPlan");
        FileUtils.writeStringToFile(planFile, "plan-content", Charset.defaultCharset());

        when(blobContainerClient.exists()).thenReturn(false);
        when(blobClient.getBlobUrl()).thenReturn("https://storage.azure.com/plan");

        azureTerraformState.saveTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        verify(blobContainerClient).create();
        verify(blobClient).uploadFromFile(planFile.getAbsolutePath(), true);
    }

    @Test
    void testSaveStateJson() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org1");
        job.setWorkspaceId("ws1");
        job.setJobId("job1");

        String applyJSON = "{\"state\": \"applied\"}";
        String rawState = "{\"state\": \"raw\"}";

        when(blobContainerClient.exists()).thenReturn(true);
        when(terraformStatePathService.getStateJsonPath(anyString(), anyString(), anyString())).thenReturn("https://api/state.json");

        azureTerraformState.saveStateJson(job, applyJSON, rawState);

        verify(blobClient, times(2)).upload(any(BinaryData.class), eq(true));
        verify(terrakubeClient).createHistory(any(HistoryRequest.class), eq("org1"), eq("ws1"));
    }

    @Test
    void testDownloadTerraformPlan(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();

        // The implementation uses URL parsing and string replacement to get the blob name.
        // URL stateUrl = new URL(stateUrl);
        // String blobName = blobURL.getPath().replace("/tfstate/", "").replace("%2F","/");
        String planUrl = "https://storage.azure.com/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan";
        when(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .thenReturn(planUrl);
        
        when(blobClient.getBlobUrl()).thenReturn("https://storage.azure.com/blob");
        when(blobClient.generateSas(any(BlobServiceSasSignatureValues.class))).thenReturn("sas-token");

        // The implementation uses FileUtils.copyURLToFile which is hard to mock directly as it's static.
        // However, we can try to mock the behavior by catching the call if it's possible or just verify up to the point of failure if it tries to connect.
        // Since I cannot mock static methods easily with standard Mockito without mockito-inline,
        // and I don't know if mockito-inline is available, I will assume it is NOT.
        // But wait, Azure SDK might be using its own mechanisms? No, the code uses FileUtils.copyURLToFile.
        
        // Let's see if I can use a local file URL to make it pass or at least not throw exception.
        // String expectedBlobUrlWithSas = "https://storage.azure.com/blob?sas-token";
        
        // Actually, FileUtils.copyURLToFile(new URL(...), file, ...) will try to connect.
        // To test this properly without real network, we might need to mock FileUtils or use a local server.
        // But here I'll just check if the logic before the call is sound.
        
        // I'll use a local file URL for testing to avoid network issues if I can.
        File sourceFile = new File(workingDirectory, "source.tfPlan");
        FileUtils.writeStringToFile(sourceFile, "source-content", Charset.defaultCharset());
        String localUrl = sourceFile.toURI().toURL().toString();
        
        when(blobClient.getBlobUrl()).thenReturn(localUrl.split("\\?")[0]);
        when(blobClient.generateSas(any())).thenReturn("");

        boolean result = azureTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertTrue(result);
        File downloadedPlan = new File(workingDirectory, "terraformLibrary.tfPlan");
        assertTrue(downloadedPlan.exists());
        assertEquals("source-content", FileUtils.readFileToString(downloadedPlan, Charset.defaultCharset()));
    }

    // #3671 follow-up: a genuine download failure must retry then throw instead of silently
    // leaving planExists false with no clear signal why.
    @Test
    void testDownloadTerraformPlan_ThrowsAfterRetryingAGenuineFailure(@TempDir Path tempDir) {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();

        String planUrl = "https://storage.azure.com/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan";
        when(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .thenReturn(planUrl);

        // A file:// URL to a path that doesn't exist makes copyURLToFile fail reliably without
        // any real network dependency, same trick the happy-path test above uses in reverse.
        when(blobClient.getBlobUrl()).thenReturn("file:///nonexistent/path/does-not-exist.tfPlan");
        when(blobClient.generateSas(any())).thenReturn("");

        assertThrows(io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException.class,
                () -> azureTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory));

        verify(blobClient, times(3)).generateSas(any());
    }

    @Test
    void testSaveBufferedOutputStreamsToStorage() throws Exception {
        when(blobContainerClient.exists()).thenReturn(true);
        when(terraformOutputPathService.getOutputPath("org1", "job1", "step1")).thenReturn("https://api/output");
        java.io.ByteArrayOutputStream uploaded = new java.io.ByteArrayOutputStream();
        org.mockito.ArgumentCaptor<Long> length = org.mockito.ArgumentCaptor.forClass(Long.class);
        doAnswer(invocation -> {
            invocation.<java.io.InputStream>getArgument(0).transferTo(uploaded);
            return null;
        }).when(blobClient).upload(any(java.io.InputStream.class), length.capture(), eq(true));

        try (StepOutputBuffer buffer = new StepOutputBuffer(4, null)) {
            buffer.appendln("output-content");
            String result = azureTerraformState.saveBufferedOutput("org1", "job1", "step1", buffer, "error-content");

            assertEquals("https://api/output", result);
        }
        assertEquals("output-content\nerror-content", uploaded.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(uploaded.size(), length.getValue());
    }

    @Test
    void testSaveOutputPartGoesUnderThePartsFolderOfTheStep() throws Exception {
        when(blobContainerClient.exists()).thenReturn(true);
        java.io.ByteArrayOutputStream uploaded = new java.io.ByteArrayOutputStream();
        org.mockito.ArgumentCaptor<Long> length = org.mockito.ArgumentCaptor.forClass(Long.class);
        doAnswer(invocation -> {
            invocation.<java.io.InputStream>getArgument(0).transferTo(uploaded);
            return null;
        }).when(blobClient).upload(any(java.io.InputStream.class), length.capture(), eq(true));

        assertTrue(azureTerraformState.saveOutputPart("org1", "job1", "step1", 3, PART_SOURCE));

        verify(blobContainerClient).getBlobClient("org1/job1/step1.parts/000003.part");
        assertEquals("part-content", uploaded.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(12L, length.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDeleteOutputPartsRemovesOnlyThePartsOfTheStep() {
        com.azure.core.http.rest.PagedIterable<com.azure.storage.blob.models.BlobItem> listed = mock(com.azure.core.http.rest.PagedIterable.class);
        java.util.List<com.azure.storage.blob.models.BlobItem> items = java.util.List.of(
                new com.azure.storage.blob.models.BlobItem().setName("org1/job1/step1.parts/000001.part"),
                new com.azure.storage.blob.models.BlobItem().setName("org1/job1/step1.parts/000002.part"));
        doReturn(items.iterator()).when(listed).iterator();
        org.mockito.ArgumentCaptor<com.azure.storage.blob.models.ListBlobsOptions> options = org.mockito.ArgumentCaptor.forClass(com.azure.storage.blob.models.ListBlobsOptions.class);
        when(blobContainerClient.listBlobs(options.capture(), any())).thenReturn(listed);

        azureTerraformState.deleteOutputParts("org1", "job1", "step1");

        assertEquals("org1/job1/step1.parts/", options.getValue().getPrefix());
        verify(blobContainerClient).getBlobClient("org1/job1/step1.parts/000001.part");
        verify(blobContainerClient).getBlobClient("org1/job1/step1.parts/000002.part");
        verify(blobClient, times(2)).deleteIfExists();
    }

    @Test
    void testDeleteOutputPartsDoesNotThrowWhenStorageFails() {
        when(blobContainerClient.listBlobs(any(com.azure.storage.blob.models.ListBlobsOptions.class), any())).thenThrow(new RuntimeException("down"));

        assertDoesNotThrow(() -> azureTerraformState.deleteOutputParts("org1", "job1", "step1"));
    }

    private static final io.terrakube.executor.service.logs.StepOutputPartWriter.PartSource PART_SOURCE = new io.terrakube.executor.service.logs.StepOutputPartWriter.PartSource() {
            public java.io.InputStream open() {
                return new java.io.ByteArrayInputStream("part-content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            public long length() {
                return 12;
            }
        };

    @Test
    void testSpoolContextPatch() {
        String organizationId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String phase = "plan";
        byte[] gzippedPatch = "dummy-gzipped-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(blobContainerClient);
        when(blobContainerClient.exists()).thenReturn(true);
        when(blobContainerClient.getBlobClient("context-spool/org1/job1/step1-plan.json.gz")).thenReturn(blobClient);

        String result = azureTerraformState.spoolContextPatch(organizationId, jobId, stepId, phase, gzippedPatch);

        assertEquals("context-spool/org1/job1/step1-plan.json.gz", result);
        verify(blobClient).upload(any(BinaryData.class), eq(true));
    }

    // ----- plan artifacts

    private static final String ARTIFACT_BLOB = "plan-artifacts/org1/ws1/job1/step1/plan-artifacts.tar.gz";

    private io.terrakube.client.model.organization.job.JobAttributes recordedArtifacts(String location, String checksum) {
        io.terrakube.client.model.organization.job.JobAttributes attributes = new io.terrakube.client.model.organization.job.JobAttributes();
        attributes.setTerraformPlanArtifacts(location);
        attributes.setTerraformPlanArtifactsChecksum(checksum);
        when(terrakubeClient.getJobById("org1", "job1").getData().getAttributes()).thenReturn(attributes);
        return attributes;
    }

    @Test
    void savingArtifactsUploadsTheBundleIntoTheStateContainerUnderItsOwnPrefix(@TempDir Path tempDir) throws Exception {
        File bundle = new File(tempDir.toFile(), "bundle.tar.gz");
        FileUtils.writeStringToFile(bundle, "tar-gz-bytes", Charset.defaultCharset());
        when(blobContainerClient.exists()).thenReturn(true);
        when(blobClient.getBlobUrl()).thenReturn("https://test-account.blob.core.windows.net/tfstate/" + ARTIFACT_BLOB);

        String location = azureTerraformState.saveArtifacts("org1", "ws1", "job1", "step1", bundle);

        assertEquals("https://test-account.blob.core.windows.net/tfstate/" + ARTIFACT_BLOB, location);
        verify(blobServiceClient, atLeastOnce()).getBlobContainerClient("tfstate");
        verify(blobContainerClient).getBlobClient(ARTIFACT_BLOB);
        verify(blobClient).uploadFromFile(bundle.getAbsolutePath(), true);
    }

    @Test
    void savingArtifactsRetriesATransientFailure(@TempDir Path tempDir) throws Exception {
        File bundle = new File(tempDir.toFile(), "bundle.tar.gz");
        FileUtils.writeStringToFile(bundle, "x", Charset.defaultCharset());
        when(blobContainerClient.exists()).thenReturn(true);
        doThrow(new RuntimeException("503")).doNothing().when(blobClient).uploadFromFile(anyString(), eq(true));

        assertDoesNotThrow(() -> azureTerraformState.saveArtifacts("org1", "ws1", "job1", "step1", bundle));

        verify(blobClient, times(2)).uploadFromFile(anyString(), eq(true));
    }

    @Test
    void downloadingArtifactsWritesTheBundleAndReturnsTheRecordedChecksum(@TempDir Path tempDir) throws Exception {
        recordedArtifacts("https://test-account.blob.core.windows.net/tfstate/" + ARTIFACT_BLOB, "abc123");
        File target = new File(tempDir.toFile(), "downloaded.tar.gz");
        doAnswer(invocation -> {
            FileUtils.writeStringToFile(new File((String) invocation.getArgument(0)), "bundle-bytes", Charset.defaultCharset());
            return null;
        }).when(blobClient).downloadToFile(anyString(), eq(true));

        assertEquals("abc123", azureTerraformState.downloadArtifacts("org1", "ws1", "job1", target));

        assertEquals("bundle-bytes", FileUtils.readFileToString(target, Charset.defaultCharset()));
        verify(blobContainerClient).getBlobClient(ARTIFACT_BLOB);
    }

    @Test
    void aJobWithoutArtifactsHasNothingToDownload(@TempDir Path tempDir) {
        recordedArtifacts(null, null);

        assertNull(azureTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));

        verifyNoInteractions(blobClient);
    }

    @Test
    void aRecordedLocationInAnotherJobOrTenantIsRefusedWithoutTouchingTheContainer(@TempDir Path tempDir) {
        recordedArtifacts("https://test-account.blob.core.windows.net/tfstate/plan-artifacts/org1/ws1/other-job/step1/plan-artifacts.tar.gz", "abc");

        assertThrows(io.terrakube.executor.plugin.tfstate.ArtifactVerificationException.class,
                () -> azureTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
        verifyNoInteractions(blobClient);
    }

    @Test
    void aBlobMissingFromTheContainerIsReportedAsMissingNotAsAnOutage(@TempDir Path tempDir) {
        recordedArtifacts("https://test-account.blob.core.windows.net/tfstate/" + ARTIFACT_BLOB, "abc123");
        com.azure.storage.blob.models.BlobStorageException notFound = mock(com.azure.storage.blob.models.BlobStorageException.class);
        when(notFound.getStatusCode()).thenReturn(404);
        doThrow(notFound).when(blobClient).downloadToFile(anyString(), eq(true));

        io.terrakube.executor.plugin.tfstate.ArtifactVerificationException thrown = assertThrows(
                io.terrakube.executor.plugin.tfstate.ArtifactVerificationException.class,
                () -> azureTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
        assertTrue(thrown.getMessage().contains("missing"));
    }

    @Test
    void anOutageWhileDownloadingIsNotMistakenForNoArtifacts(@TempDir Path tempDir) {
        recordedArtifacts("https://test-account.blob.core.windows.net/tfstate/" + ARTIFACT_BLOB, "abc123");
        doThrow(new RuntimeException("503")).when(blobClient).downloadToFile(anyString(), eq(true));

        assertThrows(io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException.class,
                () -> azureTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
    }

    @Test
    void deletingArtifactsRemovesTheRecordedBlobAndNeverFailsTheCaller() {
        recordedArtifacts("https://test-account.blob.core.windows.net/tfstate/" + ARTIFACT_BLOB, "abc123");

        azureTerraformState.deleteArtifacts("org1", "ws1", "job1");

        verify(blobClient).deleteIfExists();

        when(blobClient.deleteIfExists()).thenThrow(new RuntimeException("denied"));
        assertDoesNotThrow(() -> azureTerraformState.deleteArtifacts("org1", "ws1", "job1"));
    }
}
