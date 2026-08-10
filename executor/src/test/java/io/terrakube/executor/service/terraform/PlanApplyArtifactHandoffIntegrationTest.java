package io.terrakube.executor.service.terraform;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.executor.configuration.ArtifactProperties;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.StorageRetryMetrics;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.plugin.tfstate.local.LocalTerraformStateImpl;
import io.terrakube.executor.service.artifact.ArtifactGlobMatcher;
import io.terrakube.executor.service.artifact.ArtifactPackagingService;
import io.terrakube.executor.service.artifact.ArtifactPatternResolver;
import io.terrakube.executor.service.artifact.ArtifactRestoreService;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole hand-off with real services and real files: a plan step packages what its before-script
 * built, the bundle is stored, and an apply step in a brand new clone (another directory, nothing
 * built in it) gets it back. Only the job record the API holds is stubbed.
 */
class PlanApplyArtifactHandoffIntegrationTest {

    @TempDir
    Path home;

    private String originalHome;
    private TerrakubeClient terrakubeClient;
    private LocalTerraformStateImpl state;
    private final ArtifactProperties properties = new ArtifactProperties();
    private final TarGzArchiver archiver = new TarGzArchiver();
    private final ArtifactPackagingService packaging = new ArtifactPackagingService(
            new ArtifactPatternResolver(), new ArtifactGlobMatcher(), archiver, properties);

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        state = LocalTerraformStateImpl.builder()
                .terrakubeClient(terrakubeClient)
                .terraformOutputPathService(mock(TerraformOutputPathService.class))
                .terraformStatePathService(mock(TerraformStatePathService.class))
                .storageRetryMetrics(new StorageRetryMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .build();
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    private ArtifactRestoreService restoreService() {
        return new ArtifactRestoreService(state, archiver, properties);
    }

    private TerraformJob job(List<String> patterns, String... env) {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org1");
        job.setWorkspaceId("ws1");
        job.setJobId("job1");
        job.setStepId("plan-step");
        job.setArtifactPatterns(patterns);
        HashMap<String, String> variables = new HashMap<>();
        for (int i = 0; i < env.length; i += 2) {
            variables.put(env[i], env[i + 1]);
        }
        job.setEnvironmentVariables(variables);
        return job;
    }

    /** What the plan step does after a successful plan with changes; returns where the bundle went. */
    private String planStepStoresArtifacts(File planClone, TerraformJob planJob, File scratch) throws Exception {
        ArtifactPackagingService.Result packaged = packaging.packageArtifacts(planJob, planClone, new File(scratch, "plan-bundle.tar.gz"));
        assertEquals(ArtifactPackagingService.Status.PACKAGED, packaged.status());
        String location = state.saveArtifacts("org1", "ws1", "job1", "plan-step", packaged.bundle());
        assertNotNull(location);
        recordOnJob(location, packaged.sha256());
        return location;
    }

    private String recordedChecksum;

    private void recordOnJob(String location, String checksum) {
        recordedChecksum = checksum;
        JobAttributes attributes = new JobAttributes();
        attributes.setTerraformPlanArtifacts(location);
        attributes.setTerraformPlanArtifactsChecksum(checksum);
        when(terrakubeClient.getJobById("org1", "job1").getData().getAttributes()).thenReturn(attributes);
    }

    private TerraformJob applyJob() {
        TerraformJob job = job(null);
        job.setStepId("apply-step");
        return job;
    }

    @Test
    void whatThePlanStepBuiltIsInTheApplyStepsFreshClone(@TempDir Path planClone, @TempDir Path applyClone, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(planClone.toFile(), "build/forwarder.zip"), "zip-bytes", Charset.defaultCharset());
        FileUtils.writeStringToFile(new File(planClone.toFile(), "build/sub/layer.zip"), "layer-bytes", Charset.defaultCharset());
        FileUtils.writeStringToFile(new File(planClone.toFile(), "main.tf"), "resource", Charset.defaultCharset());
        planStepStoresArtifacts(planClone.toFile(), job(List.of("${ARTIFACT_DIR}/**"), "ARTIFACT_DIR", "build"), scratch.toFile());
        String planSha256 = recordedChecksum;

        ArtifactRestoreService.Result restored = restoreService().restore(applyJob(), applyClone.toFile(), scratch.toFile());
        assertTrue(restored.restored());
        assertEquals(2, restored.files());
        assertEquals(planSha256, restored.sha256(), "apply restored the bundle the plan step hashed");

        assertEquals("zip-bytes", Files.readString(applyClone.resolve("build/forwarder.zip")));
        assertEquals("layer-bytes", Files.readString(applyClone.resolve("build/sub/layer.zip")));
        assertFalse(Files.exists(applyClone.resolve("main.tf")), "only what the patterns matched");
    }

    @Test
    void aWorkspaceVariableValueWithSeveralPatternsOnSeparateLinesWorksEndToEnd(@TempDir Path planClone, @TempDir Path applyClone, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(planClone.toFile(), "dist/a.zip"), "a", Charset.defaultCharset());
        FileUtils.writeStringToFile(new File(planClone.toFile(), "layers/b.zip"), "b", Charset.defaultCharset());
        FileUtils.writeStringToFile(new File(planClone.toFile(), "other/c.txt"), "c", Charset.defaultCharset());
        // exactly what the API passes on for TERRAKUBE_PLAN_ARTIFACTS
        planStepStoresArtifacts(planClone.toFile(), job(List.of("dist/*.zip\nlayers/**")), scratch.toFile());

        restoreService().restore(applyJob(), applyClone.toFile(), scratch.toFile());

        assertTrue(Files.exists(applyClone.resolve("dist/a.zip")));
        assertTrue(Files.exists(applyClone.resolve("layers/b.zip")));
        assertFalse(Files.exists(applyClone.resolve("other/c.txt")));
    }

    @Test
    void aLambdaBootstrapScriptIsStillExecutableAfterTheHandoff(@TempDir Path planClone, @TempDir Path applyClone, @TempDir Path scratch) throws Exception {
        File bootstrap = new File(planClone.toFile(), "build/bootstrap");
        FileUtils.writeStringToFile(bootstrap, "#!/bin/sh\n", Charset.defaultCharset());
        assertTrue(bootstrap.setExecutable(true));
        planStepStoresArtifacts(planClone.toFile(), job(List.of("build/**")), scratch.toFile());

        restoreService().restore(applyJob(), applyClone.toFile(), scratch.toFile());

        assertTrue(applyClone.resolve("build/bootstrap").toFile().canExecute());
    }

    @Test
    void aBundleThatChangedInStorageIsRefusedBeforeAnythingIsUnpacked(@TempDir Path planClone, @TempDir Path applyClone, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(planClone.toFile(), "build/forwarder.zip"), "zip-bytes", Charset.defaultCharset());
        String location = planStepStoresArtifacts(planClone.toFile(), job(List.of("build/**")), scratch.toFile());
        // someone, or a failing disk, alters the stored object
        Files.write(Path.of(location), "not the bundle that was uploaded".getBytes());

        ArtifactVerificationException thrown = assertThrows(ArtifactVerificationException.class,
                () -> restoreService().restore(applyJob(), applyClone.toFile(), scratch.toFile()));

        assertTrue(thrown.getMessage().contains("checksum mismatch"));
        assertFalse(Files.exists(applyClone.resolve("build/forwarder.zip")));
    }

    @Test
    void aJobRecordPointingAtAnotherJobsBundleIsRefused(@TempDir Path planClone, @TempDir Path applyClone, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(planClone.toFile(), "build/forwarder.zip"), "zip-bytes", Charset.defaultCharset());
        planStepStoresArtifacts(planClone.toFile(), job(List.of("build/**")), scratch.toFile());
        // a bundle that belongs to another organization's job
        TerraformJob victim = job(List.of("build/**"));
        victim.setOrganizationId("victim-org");
        File victimBundle = new File(scratch.toFile(), "victim.tar.gz");
        packaging.packageArtifacts(victim, planClone.toFile(), victimBundle);
        String victimLocation = state.saveArtifacts("victim-org", "victim-ws", "job1", "plan-step", victimBundle);
        recordOnJob(victimLocation, org.apache.commons.codec.digest.DigestUtils.sha256Hex(Files.readAllBytes(victimBundle.toPath())));

        assertThrows(ArtifactVerificationException.class,
                () -> restoreService().restore(applyJob(), applyClone.toFile(), scratch.toFile()));

        assertFalse(Files.exists(applyClone.resolve("build/forwarder.zip")));
    }

    @Test
    void aBundleLargerThanTheLimitIsNeverUnpackedEvenWithAValidChecksum(@TempDir Path planClone, @TempDir Path applyClone, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(planClone.toFile(), "build/big.bin"), "x".repeat(2_000), Charset.defaultCharset());
        planStepStoresArtifacts(planClone.toFile(), job(List.of("build/**")), scratch.toFile());
        properties.setMaxBytes(1_000);

        assertThrows(ArtifactVerificationException.class,
                () -> restoreService().restore(applyJob(), applyClone.toFile(), scratch.toFile()));

        assertFalse(Files.exists(applyClone.resolve("build/big.bin")) && Files.size(applyClone.resolve("build/big.bin")) == 2_000);
    }

    @Test
    void aSecondPlanWithoutArtifactsLeavesNothingForItsApplyToUnpack(@TempDir Path planClone, @TempDir Path applyClone, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(planClone.toFile(), "build/forwarder.zip"), "zip-bytes", Charset.defaultCharset());
        planStepStoresArtifacts(planClone.toFile(), job(List.of("build/**")), scratch.toFile());
        // the later plan recorded no artifacts (UpdateJobStatusImpl sends an empty value to replace them)
        recordOnJob("", "");

        assertFalse(restoreService().restore(applyJob(), applyClone.toFile(), scratch.toFile()).restored());

        assertFalse(Files.exists(applyClone.resolve("build/forwarder.zip")));
    }

    @Test
    void afterAnApplyTheBundleIsDeleted(@TempDir Path planClone, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(planClone.toFile(), "build/forwarder.zip"), "zip-bytes", Charset.defaultCharset());
        String location = planStepStoresArtifacts(planClone.toFile(), job(List.of("build/**")), scratch.toFile());

        state.deleteArtifacts("org1", "ws1", "job1");

        assertFalse(Files.exists(Path.of(location)));
    }
}
