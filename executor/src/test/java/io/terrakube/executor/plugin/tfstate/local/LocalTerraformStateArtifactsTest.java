package io.terrakube.executor.plugin.tfstate.local;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.StorageRetryMetrics;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Plan artifacts on the local backend, with the executor's home directory pointed at a temp directory. */
class LocalTerraformStateArtifactsTest {

    private static final String ARTIFACT_PATH = "/.terraform-spring-boot/local/plan-artifacts/org1/ws1/job1/step1/plan-artifacts.tar.gz";

    @TempDir
    Path home;

    private String originalHome;
    private TerrakubeClient terrakubeClient;
    private LocalTerraformStateImpl state;

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

    private void recorded(String location, String checksum) {
        JobAttributes attributes = new JobAttributes();
        attributes.setTerraformPlanArtifacts(location);
        attributes.setTerraformPlanArtifactsChecksum(checksum);
        when(terrakubeClient.getJobById("org1", "job1").getData().getAttributes()).thenReturn(attributes);
    }

    private File bundle(Path dir, String content) throws Exception {
        File bundle = new File(dir.toFile(), "bundle.tar.gz");
        FileUtils.writeStringToFile(bundle, content, Charset.defaultCharset());
        return bundle;
    }

    @Test
    void aSavedBundleIsStoredUnderItsOwnDirectoryAndComesBack(@TempDir Path work) throws Exception {
        String location = state.saveArtifacts("org1", "ws1", "job1", "step1", bundle(work, "bundle-bytes"));

        assertEquals(home + ARTIFACT_PATH, location);
        assertTrue(new File(location).isFile());
        assertFalse(location.contains("/local/state/"), "kept apart from plan and state history");

        recorded(location, "abc123");
        File target = new File(work.toFile(), "downloaded.tar.gz");
        assertEquals("abc123", state.downloadArtifacts("org1", "ws1", "job1", target));
        assertEquals("bundle-bytes", FileUtils.readFileToString(target, Charset.defaultCharset()));
    }

    @Test
    void aJobWithoutArtifactsHasNothingToDownload(@TempDir Path work) {
        recorded(null, null);

        assertNull(state.downloadArtifacts("org1", "ws1", "job1", new File(work.toFile(), "x")));
    }

    @Test
    void aMissingBundleExplainsWhatLocalStorageNeedsAcrossExecutors(@TempDir Path work) {
        recorded(home + ARTIFACT_PATH, "abc123");

        ArtifactVerificationException thrown = assertThrows(ArtifactVerificationException.class,
                () -> state.downloadArtifacts("org1", "ws1", "job1", new File(work.toFile(), "x")));

        assertTrue(thrown.getMessage().contains("does not exist on this executor"));
        assertTrue(thrown.getMessage().contains("share"));
    }

    @Test
    void aRecordedPathToSomeOtherFileIsRefusedNotRead(@TempDir Path work) throws Exception {
        File other = new File(work.toFile(), "secret.txt");
        FileUtils.writeStringToFile(other, "secret", Charset.defaultCharset());
        recorded(other.getAbsolutePath(), "abc");

        assertThrows(ArtifactVerificationException.class,
                () -> state.downloadArtifacts("org1", "ws1", "job1", new File(work.toFile(), "x")));
        assertFalse(new File(work.toFile(), "x").exists());
    }

    @Test
    void aRecordedPathInAnotherTenantsDirectoryIsRefused(@TempDir Path work) throws Exception {
        String otherTenant = home + "/.terraform-spring-boot/local/plan-artifacts/other-org/ws1/job1/step1/plan-artifacts.tar.gz";
        FileUtils.writeStringToFile(new File(otherTenant), "theirs", Charset.defaultCharset());
        recorded(otherTenant, "abc");

        assertThrows(ArtifactVerificationException.class,
                () -> state.downloadArtifacts("org1", "ws1", "job1", new File(work.toFile(), "x")));
    }

    @Test
    void deletingRemovesTheBundleAndIsHarmlessWhenThereIsNone(@TempDir Path work) throws Exception {
        String location = state.saveArtifacts("org1", "ws1", "job1", "step1", bundle(work, "bundle-bytes"));
        recorded(location, "abc123");

        state.deleteArtifacts("org1", "ws1", "job1");

        assertFalse(new File(location).exists());
        assertDoesNotThrow(() -> state.deleteArtifacts("org1", "ws1", "job1"));
        recorded(null, null);
        assertDoesNotThrow(() -> state.deleteArtifacts("org1", "ws1", "job1"));
        assertTrue(Files.exists(home));
    }
}
