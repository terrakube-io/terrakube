package io.terrakube.executor.service.terraform;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.plugin.tfstate.local.LocalTerraformStateImpl;
import io.terrakube.executor.service.artifact.ArtifactGlobMatcher;
import io.terrakube.executor.service.artifact.ArtifactPackagingService;
import io.terrakube.executor.service.artifact.ArtifactPatternResolver;
import io.terrakube.executor.service.artifact.ArtifactVerifier;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanApplyArtifactHandoffIntegrationTest {

    private final TarGzArchiver tarGzArchiver = new TarGzArchiver();
    private final ArtifactPackagingService packagingService =
            new ArtifactPackagingService(new ArtifactPatternResolver(), new ArtifactGlobMatcher(), tarGzArchiver);
    private final ArtifactVerifier verifier = new ArtifactVerifier(tarGzArchiver);

    @Test
    void planStepArtifactsSurviveHandoffToFreshApplyClone(@TempDir Path planDir, @TempDir Path applyDir) throws Exception {
        // Simulate the plan step's working directory: a before-script produced a build output.
        File planWorkingDirectory = planDir.toFile();
        File builtZip = new File(planWorkingDirectory, "build/forwarder.zip");
        FileUtils.writeStringToFile(builtZip, "zip-bytes", Charset.defaultCharset());

        TerraformJob terraformJob = new TerraformJob();
        terraformJob.setOrganizationId("org1");
        terraformJob.setWorkspaceId("ws1");
        terraformJob.setJobId("job1");
        terraformJob.setStepId("step1");
        terraformJob.setArtifactPatterns(List.of("${ARTIFACT_PATH}/**"));
        HashMap<String, String> env = new HashMap<>();
        env.put("ARTIFACT_PATH", "build");
        terraformJob.setEnvironmentVariables(env);

        Optional<String> checksum = packagingService.packageArtifacts(terraformJob, planWorkingDirectory);
        assertTrue(checksum.isPresent());

        TerrakubeClient terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        TerraformOutputPathService outputPathService = mock(TerraformOutputPathService.class);
        TerraformStatePathService statePathService = mock(TerraformStatePathService.class);
        LocalTerraformStateImpl terraformState = LocalTerraformStateImpl.builder()
                .terrakubeClient(terrakubeClient)
                .terraformOutputPathService(outputPathService)
                .terraformStatePathService(statePathService)
                .artifactVerifier(verifier)
                .build();

        String artifactsPath = terraformState.saveArtifacts("org1", "ws1", "job1", "step1", planWorkingDirectory);
        assertNotNull(artifactsPath);

        // Simulate apply's fresh clone: a brand new, empty directory that never ran the build.
        File applyWorkingDirectory = applyDir.toFile();
        JobAttributes attributes = new JobAttributes();
        attributes.setTerraformPlanArtifacts(artifactsPath);
        attributes.setTerraformPlanArtifactsChecksum(checksum.get());
        when(terrakubeClient.getJobById("org1", "job1").getData().getAttributes()).thenReturn(attributes);

        boolean downloaded = terraformState.downloadArtifacts("org1", "ws1", "job1", "step1", applyWorkingDirectory);

        assertTrue(downloaded);
        File extractedZip = new File(applyWorkingDirectory, "build/forwarder.zip");
        assertTrue(extractedZip.exists());
        assertEquals("zip-bytes", FileUtils.readFileToString(extractedZip, Charset.defaultCharset()));
    }

    @Test
    void tamperedChecksumFailsBeforeExtractingIntoApplyClone(@TempDir Path planDir, @TempDir Path applyDir) throws Exception {
        File planWorkingDirectory = planDir.toFile();
        File builtZip = new File(planWorkingDirectory, "build/forwarder.zip");
        FileUtils.writeStringToFile(builtZip, "zip-bytes", Charset.defaultCharset());

        TerraformJob terraformJob = new TerraformJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));
        terraformJob.setEnvironmentVariables(new HashMap<>());
        packagingService.packageArtifacts(terraformJob, planWorkingDirectory);

        TerrakubeClient terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        LocalTerraformStateImpl terraformState = LocalTerraformStateImpl.builder()
                .terrakubeClient(terrakubeClient)
                .terraformOutputPathService(mock(TerraformOutputPathService.class))
                .terraformStatePathService(mock(TerraformStatePathService.class))
                .artifactVerifier(verifier)
                .build();

        String artifactsPath = terraformState.saveArtifacts("org1", "ws1", "job1", "step1", planWorkingDirectory);

        File applyWorkingDirectory = applyDir.toFile();
        JobAttributes attributes = new JobAttributes();
        attributes.setTerraformPlanArtifacts(artifactsPath);
        attributes.setTerraformPlanArtifactsChecksum("tampered-checksum-value");
        when(terrakubeClient.getJobById("org1", "job1").getData().getAttributes()).thenReturn(attributes);

        assertThrows(ArtifactVerificationException.class,
                () -> terraformState.downloadArtifacts("org1", "ws1", "job1", "step1", applyWorkingDirectory));

        assertFalse(new File(applyWorkingDirectory, "build/forwarder.zip").exists());
    }
}
