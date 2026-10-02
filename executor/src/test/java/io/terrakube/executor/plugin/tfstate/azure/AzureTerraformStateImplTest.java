package io.terrakube.executor.plugin.tfstate.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
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
        assertFalse(content.contains("use_azuread_auth"));
    }

    @Test
    void testGetBackendStateFileWithoutAccessKeyUsesEntraId(@TempDir Path tempDir) throws IOException {
        azureTerraformState.setStorageAccessKey("");
        File workingDirectory = tempDir.toFile();

        azureTerraformState.getBackendStateFile("org1", "ws1", workingDirectory, "1.5.0");

        String content = FileUtils.readFileToString(new File(workingDirectory, "azure_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("use_azuread_auth     = true"));
        assertFalse(content.contains("access_key"));
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
        verify(blobClient).uploadFromFile(planFile.getAbsolutePath());
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
        verify(blobClient).uploadFromFile(planFile.getAbsolutePath());
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

        verify(blobClient, times(2)).upload(any(BinaryData.class));
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
        String planPath = workingDirectory.getAbsolutePath() + "/terraformLibrary.tfPlan";
        doAnswer(invocation -> {
            FileUtils.writeStringToFile(new File(planPath), "source-content", Charset.defaultCharset());
            return null;
        }).when(blobClient).downloadToFile(planPath, true);

        boolean result = azureTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertTrue(result);
        verify(blobContainerClient).getBlobClient("org1/ws1/job1/step1/terraformLibrary.tfPlan");
        verify(blobClient, never()).generateSas(any());
        File downloadedPlan = new File(workingDirectory, "terraformLibrary.tfPlan");
        assertTrue(downloadedPlan.exists());
        assertEquals("source-content", FileUtils.readFileToString(downloadedPlan, Charset.defaultCharset()));
    }

    @Test
    void testSaveOutput() {
        String organizationId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String output = "output-content";
        String outputError = "error-content";

        when(blobContainerClient.exists()).thenReturn(true);
        when(terraformOutputPathService.getOutputPath(organizationId, jobId, stepId)).thenReturn("https://api/output");

        String result = azureTerraformState.saveOutput(organizationId, jobId, stepId, output, outputError);

        assertEquals("https://api/output", result);
        verify(blobClient).upload(any(BinaryData.class));
    }
}
