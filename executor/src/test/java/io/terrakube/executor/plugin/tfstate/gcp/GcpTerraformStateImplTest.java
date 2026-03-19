package io.terrakube.executor.plugin.tfstate.gcp;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GcpTerraformStateImplTest {

    private Storage storage;
    private TerrakubeClient terrakubeClient;
    private TerraformOutputPathService terraformOutputPathService;
    private TerraformStatePathService terraformStatePathService;
    private GcpTerraformStateImpl gcpTerraformState;

    private final String bucketName = "test-bucket";
    private final String credentials = Base64.encodeBase64String("{\"test\": \"credentials\"}".getBytes());

    @BeforeEach
    void setUp() {
        storage = mock(Storage.class);
        terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        terraformOutputPathService = mock(TerraformOutputPathService.class);
        terraformStatePathService = mock(TerraformStatePathService.class);

        gcpTerraformState = GcpTerraformStateImpl.builder()
                .bucketName(bucketName)
                .credentials(credentials)
                .storage(storage)
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

        String result = gcpTerraformState.getBackendStateFile(organizationId, workspaceId, workingDirectory, terraformVersion);

        assertEquals("gcp_backend_override.tf", result);
        
        File credentialsFile = new File(workingDirectory, "GCP_CREDENTIALS_FILE.json");
        assertTrue(credentialsFile.exists());
        assertEquals("{\"test\": \"credentials\"}", FileUtils.readFileToString(credentialsFile, Charset.defaultCharset()));

        File backendFile = new File(workingDirectory, "gcp_backend_override.tf");
        assertTrue(backendFile.exists());
        String content = FileUtils.readFileToString(backendFile, Charset.defaultCharset());
        assertTrue(content.contains("bucket      = \"test-bucket\""));
        assertTrue(content.contains("prefix      = \"tfstate/org1/ws1/terraform.tfstate\""));
        assertTrue(content.contains("credentials = \"GCP_CREDENTIALS_FILE.json\""));
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

        String result = gcpTerraformState.saveTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        String expectedUrl = "https://storage.cloud.google.com/test-bucket/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan";
        assertEquals(expectedUrl, result);
        verify(storage).create(any(BlobInfo.class), eq("plan-content".getBytes()));
    }

    @Test
    void testDownloadTerraformPlan(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();

        String planUrl = "https://storage.cloud.google.com/test-bucket/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan";
        when(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .thenReturn(planUrl);
        
        // Mocking signUrl to return a local file URL for verification
        File sourceFile = new File(workingDirectory, "source.tfPlan");
        FileUtils.writeStringToFile(sourceFile, "source-content", Charset.defaultCharset());
        URL localUrl = sourceFile.toURI().toURL();
        
        when(storage.signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class))).thenReturn(localUrl);

        boolean result = gcpTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertTrue(result);
        File downloadedPlan = new File(workingDirectory, "terraformLibrary.tfPlan");
        assertTrue(downloadedPlan.exists());
        assertEquals("source-content", FileUtils.readFileToString(downloadedPlan, Charset.defaultCharset()));
        
        ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).signUrl(blobInfoCaptor.capture(), eq(5L), eq(TimeUnit.MINUTES));
        assertEquals("test-bucket", blobInfoCaptor.getValue().getBucket());
        // The path in URL starts after bucket name in this implementation
        // stateUrl = https://storage.cloud.google.com/test-bucket/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan
        // new URL(stateUrl).getPath() = /test-bucket/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan
        // replace("/test-bucket/", "") = tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan
        assertEquals("tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan", blobInfoCaptor.getValue().getBlobId().getName());
    }

    @Test
    void testSaveStateJson() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org1");
        job.setWorkspaceId("ws1");
        job.setJobId("job1");

        String applyJSON = "{\"state\": \"applied\"}";
        String rawState = "{\"state\": \"raw\"}";

        when(terraformStatePathService.getStateJsonPath(anyString(), anyString(), anyString())).thenReturn("https://api/state.json");

        gcpTerraformState.saveStateJson(job, applyJSON, rawState);

        verify(storage, times(2)).create(any(BlobInfo.class), any(byte[].class));
        verify(terrakubeClient).createHistory(any(HistoryRequest.class), eq("org1"), eq("ws1"));
    }

    @Test
    void testSaveOutput() {
        String organizationId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String output = "output-content";
        String outputError = "error-content";

        when(terraformOutputPathService.getOutputPath(organizationId, jobId, stepId)).thenReturn("https://api/output");

        String result = gcpTerraformState.saveOutput(organizationId, jobId, stepId, output, outputError);

        assertEquals("https://api/output", result);
        verify(storage).create(any(BlobInfo.class), eq("output-contenterror-content".getBytes()));
    }
}
