package io.terrakube.executor.plugin.tfstate.aws;

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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AwsTerraformStateImplTest {

    private S3Client s3Client;
    private TerrakubeClient terrakubeClient;
    private TerraformOutputPathService terraformOutputPathService;
    private TerraformStatePathService terraformStatePathService;
    private AwsTerraformStateImpl awsTerraformState;

    private String bucketName = "test-bucket";
    private Region region = Region.US_EAST_1;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        terraformOutputPathService = mock(TerraformOutputPathService.class);
        terraformStatePathService = mock(TerraformStatePathService.class);

        awsTerraformState = AwsTerraformStateImpl.builder()
                .s3client(s3Client)
                .bucketName(bucketName)
                .region(region)
                .terrakubeClient(terrakubeClient)
                .terraformOutputPathService(terraformOutputPathService)
                .terraformStatePathService(terraformStatePathService)
                .includeBackendKeys(false)
                .build();
    }

    @Test
    void testGetBackendStateFile_LegacyVersion(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String terraformVersion = "1.5.0";
        File workingDirectory = tempDir.toFile();

        String result = awsTerraformState.getBackendStateFile(organizationId, workspaceId, workingDirectory, terraformVersion);

        assertEquals("aws_backend_override.tf", result);
        File backendFile = new File(workingDirectory, "aws_backend_override.tf");
        assertTrue(backendFile.exists());
        String content = FileUtils.readFileToString(backendFile, Charset.defaultCharset());
        assertTrue(content.contains("bucket     = \"test-bucket\""));
        assertTrue(content.contains("region     = \"us-east-1\""));
        assertTrue(content.contains("key        = \"tfstate/org1/ws1/terraform.tfstate\""));
        assertFalse(content.contains("access_key"));
    }

    @Test
    void testGetBackendStateFile_NewVersionWithEndpoint(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("http://localhost:4566");
        awsTerraformState.setIncludeBackendKeys(true);
        awsTerraformState.setAccessKey("access");
        awsTerraformState.setSecretKey("secret");
        
        String organizationId = "org1";
        String workspaceId = "ws1";
        String terraformVersion = "1.6.0";
        File workingDirectory = tempDir.toFile();

        String result = awsTerraformState.getBackendStateFile(organizationId, workspaceId, workingDirectory, terraformVersion);

        assertEquals("aws_backend_override.tf", result);
        String content = FileUtils.readFileToString(new File(workingDirectory, "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("endpoints = {"));
        assertTrue(content.contains("s3 = \"http://localhost:4566\""));
        assertTrue(content.contains("access_key = \"access\""));
        assertTrue(content.contains("secret_key = \"secret\""));
    }

    @Test
    void testSaveTerraformPlan(@TempDir Path tempDir) throws MalformedURLException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();
        File planFile = new File(workingDirectory, "terraformLibrary.tfPlan");
        try {
            FileUtils.writeStringToFile(planFile, "plan-content", Charset.defaultCharset());
        } catch (IOException e) {
            fail("Could not create plan file");
        }

        S3Utilities s3Utilities = mock(S3Utilities.class);
        when(s3Client.utilities()).thenReturn(s3Utilities);
        when(s3Utilities.getUrl(any(GetUrlRequest.class))).thenReturn(new URL("https://s3.amazonaws.com/test-bucket/plan"));

        String url = awsTerraformState.saveTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertEquals("https://s3.amazonaws.com/test-bucket/plan", url);
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testDownloadTerraformPlan(@TempDir Path tempDir) throws MalformedURLException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();
        
        when(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .thenReturn("https://s3.amazonaws.com/test-bucket/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan");
        
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn("plan-content".getBytes());
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(responseBytes);

        boolean result = awsTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertTrue(result);
        File downloadedPlan = new File(workingDirectory, "terraformLibrary.tfPlan");
        assertTrue(downloadedPlan.exists());
    }

    @Test
    void testSaveStateJson() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org1");
        job.setWorkspaceId("ws1");
        job.setJobId("job1");
        
        String applyJSON = "{\"state\": \"applied\"}";
        String rawState = "{\"state\": \"raw\"}";

        when(terraformStatePathService.getStateJsonPath(anyString(), anyString(), anyString())).thenReturn("http://api/state.json");

        awsTerraformState.saveStateJson(job, applyJSON, rawState);

        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(terrakubeClient).createHistory(any(HistoryRequest.class), eq("org1"), eq("ws1"));
    }

    @Test
    void testSaveOutput() throws MalformedURLException {
        String organizationId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String output = "output-content";
        String outputError = "error-content";

        S3Utilities s3Utilities = mock(S3Utilities.class);
        when(s3Client.utilities()).thenReturn(s3Utilities);
        when(s3Utilities.getUrl(any(GetUrlRequest.class))).thenReturn(new URL("https://s3.amazonaws.com/test-bucket/output"));
        when(terraformOutputPathService.getOutputPath(organizationId, jobId, stepId)).thenReturn("http://api/output");

        String result = awsTerraformState.saveOutput(organizationId, jobId, stepId, output, outputError);

        assertEquals("http://api/output", result);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
