package io.terrakube.executor.plugin.tfstate.local;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalTerraformStateImplTest {

    private TerrakubeClient terrakubeClient;
    private TerraformOutputPathService terraformOutputPathService;
    private TerraformStatePathService terraformStatePathService;
    private LocalTerraformStateImpl localTerraformState;

    @BeforeEach
    void setUp() {
        terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        terraformOutputPathService = mock(TerraformOutputPathService.class);
        terraformStatePathService = mock(TerraformStatePathService.class);

        localTerraformState = LocalTerraformStateImpl.builder()
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

        String result = localTerraformState.getBackendStateFile(organizationId, workspaceId, workingDirectory, terraformVersion);

        assertEquals("terrakube_override.tf", result);
        File backendFile = new File(workingDirectory, "terrakube_override.tf");
        assertTrue(backendFile.exists());
        
        String content = FileUtils.readFileToString(backendFile, Charset.defaultCharset());
        assertTrue(content.contains("backend \"local\""));
        
        String expectedPath = FileUtils.getUserDirectoryPath().concat(
                FilenameUtils.separatorsToSystem(
                        String.format("/.terraform-spring-boot/local/backend/%s/%s/terraform.tfstate", organizationId, workspaceId)));
        assertTrue(content.contains("path                  = \"" + expectedPath + "\""));
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

        String result = localTerraformState.saveTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertNotNull(result);
        assertTrue(result.contains(".terraform-spring-boot/local/state/org1/ws1/job1/step1/terraformLibrary.tfPlan"));
        
        File savedPlan = new File(result);
        assertTrue(savedPlan.exists());
        assertEquals("plan-content", FileUtils.readFileToString(savedPlan, Charset.defaultCharset()));
        
        // Cleanup
        FileUtils.deleteQuietly(new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot"));
    }

    @Test
    void testDownloadTerraformPlan(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();
        
        File remotePlanDir = new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot/test/local/plan");
        remotePlanDir.mkdirs();
        File remotePlanFile = new File(remotePlanDir, "terraformLibrary.tfPlan");
        FileUtils.writeStringToFile(remotePlanFile, "remote-plan-content", Charset.defaultCharset());

        when(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .thenReturn(remotePlanFile.getAbsolutePath());

        boolean result = localTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertTrue(result);
        File downloadedPlan = new File(workingDirectory, "terraformLibrary.tfPlan");
        assertTrue(downloadedPlan.exists());
        assertEquals("remote-plan-content", FileUtils.readFileToString(downloadedPlan, Charset.defaultCharset()));
        
        // Cleanup
        FileUtils.deleteQuietly(new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot"));
    }

    @Test
    void testSaveStateJson() throws IOException {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org1");
        job.setWorkspaceId("ws1");
        job.setJobId("job1");

        String applyJSON = "{\"state\": \"applied\"}";
        String rawState = "{\"state\": \"raw\"}";

        when(terraformStatePathService.getStateJsonPath(anyString(), anyString(), anyString())).thenReturn("http://api/state.json");

        localTerraformState.saveStateJson(job, applyJSON, rawState);

        verify(terrakubeClient).createHistory(any(HistoryRequest.class), eq("org1"), eq("ws1"));
        
        // We can't easily know the UUID generated, but we know where it should be saved.
        // It's in ~/.terraform-spring-boot/local/state/org1/ws1/state/
        File stateDir = new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot/local/state/org1/ws1/state/");
        assertTrue(stateDir.exists() && stateDir.isDirectory());
        File[] files = stateDir.listFiles();
        assertNotNull(files);
        assertTrue(files.length >= 2); // One .json and one .raw.json
        
        // Cleanup
        FileUtils.deleteQuietly(new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot"));
    }

    @Test
    void testSaveOutput() throws IOException {
        String organizationId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String output = "output-content";
        String outputError = "error-content";

        when(terraformOutputPathService.getOutputPath(organizationId, jobId, stepId)).thenReturn("http://api/output");

        String result = localTerraformState.saveOutput(organizationId, jobId, stepId, output, outputError);

        assertEquals("http://api/output", result);
        
        String expectedOutputPath = FileUtils.getUserDirectoryPath().concat(
                FilenameUtils.separatorsToSystem(
                        String.format("/.terraform-spring-boot/local/output/%s/%s/%s.tfoutput", organizationId, jobId, stepId)));
        File outputFile = new File(expectedOutputPath);
        assertTrue(outputFile.exists());
        assertEquals("output-contenterror-content", FileUtils.readFileToString(outputFile, Charset.defaultCharset()));
        
        // Cleanup
        FileUtils.deleteQuietly(new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot"));
    }
}
