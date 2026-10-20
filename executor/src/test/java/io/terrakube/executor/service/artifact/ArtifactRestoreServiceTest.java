package io.terrakube.executor.service.artifact;

import io.terrakube.executor.configuration.ArtifactProperties;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactRestoreServiceTest {

    private final TerraformState terraformState = mock(TerraformState.class);
    private final TarGzArchiver archiver = new TarGzArchiver();
    private final ArtifactProperties properties = new ArtifactProperties();
    private final ArtifactRestoreService service = new ArtifactRestoreService(terraformState, archiver, properties);

    private TerraformJob job() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org");
        job.setWorkspaceId("ws");
        job.setJobId("7");
        job.setStepId("9");
        return job;
    }

    /** A real bundle holding build/forwarder.zip, as a plan step would have stored it. */
    private File bundle(Path dir) throws Exception {
        File built = new File(dir.toFile(), "plan-clone/build/forwarder.zip");
        FileUtils.writeStringToFile(built, "zip-bytes", Charset.defaultCharset());
        File bundle = new File(dir.toFile(), "stored.tar.gz");
        archiver.create(bundle, new File(dir.toFile(), "plan-clone"), List.of(built));
        return bundle;
    }

    private void storageHolds(File stored, String checksum) {
        doAnswer(invocation -> {
            FileUtils.copyFile(stored, (File) invocation.getArgument(3));
            return checksum;
        }).when(terraformState).downloadArtifacts(eq("org"), eq("ws"), eq("7"), any(File.class));
    }

    private long leftoverBundles(Path scratch) throws Exception {
        try (Stream<Path> files = Files.list(scratch)) {
            return files.count();
        }
    }

    @Test
    void aJobWithoutArtifactsRestoresNothing(@TempDir Path clone, @TempDir Path scratch) {
        when(terraformState.downloadArtifacts(eq("org"), eq("ws"), eq("7"), any(File.class))).thenReturn(null);

        assertEquals(ArtifactRestoreService.Result.NOTHING, service.restore(job(), clone.toFile(), scratch.toFile()));
    }

    @Test
    void theBundleIsVerifiedThenUnpackedIntoTheFreshClone(@TempDir Path stored, @TempDir Path clone, @TempDir Path scratch) throws Exception {
        File bundle = bundle(stored);
        String checksum = DigestUtils.sha256Hex(Files.readAllBytes(bundle.toPath()));
        storageHolds(bundle, checksum);

        ArtifactRestoreService.Result result = service.restore(job(), clone.toFile(), scratch.toFile());

        assertTrue(result.restored());
        assertEquals(1, result.files());
        assertEquals(checksum, result.sha256());
        assertEquals("zip-bytes", Files.readString(clone.resolve("build/forwarder.zip")));
        assertEquals(0, leftoverBundles(scratch), "the downloaded bundle is removed");
    }

    @Test
    void theChecksumComparisonIgnoresCase(@TempDir Path stored, @TempDir Path clone, @TempDir Path scratch) throws Exception {
        File bundle = bundle(stored);
        storageHolds(bundle, DigestUtils.sha256Hex(Files.readAllBytes(bundle.toPath())).toUpperCase());

        assertTrue(service.restore(job(), clone.toFile(), scratch.toFile()).restored());
    }

    @Test
    void aBundleThatDoesNotMatchItsChecksumIsNeverUnpacked(@TempDir Path stored, @TempDir Path clone, @TempDir Path scratch) throws Exception {
        storageHolds(bundle(stored), "0".repeat(64));

        ArtifactVerificationException thrown = assertThrows(ArtifactVerificationException.class,
                () -> service.restore(job(), clone.toFile(), scratch.toFile()));

        assertTrue(thrown.getMessage().contains("checksum mismatch"));
        assertFalse(Files.exists(clone.resolve("build/forwarder.zip")));
        assertEquals(0, leftoverBundles(scratch));
    }

    @Test
    void artifactsRecordedWithoutAChecksumCannotBeVerifiedSoTheyFail(@TempDir Path stored, @TempDir Path clone, @TempDir Path scratch) throws Exception {
        storageHolds(bundle(stored), "");

        assertThrows(ArtifactVerificationException.class, () -> service.restore(job(), clone.toFile(), scratch.toFile()));

        assertFalse(Files.exists(clone.resolve("build/forwarder.zip")));
    }

    @Test
    void aBundleThatUnpacksToMoreThanTheLimitFails(@TempDir Path stored, @TempDir Path clone, @TempDir Path scratch) throws Exception {
        File bundle = bundle(stored);
        storageHolds(bundle, DigestUtils.sha256Hex(Files.readAllBytes(bundle.toPath())));
        properties.setMaxBytes(3);

        assertThrows(ArtifactVerificationException.class, () -> service.restore(job(), clone.toFile(), scratch.toFile()));

        assertEquals(0, leftoverBundles(scratch));
    }

    @Test
    void aMissingBundleFailsTheRestoreWithTheBackendsMessage(@TempDir Path clone, @TempDir Path scratch) {
        when(terraformState.downloadArtifacts(eq("org"), eq("ws"), eq("7"), any(File.class)))
                .thenThrow(new ArtifactVerificationException("The plan artifacts bundle x is missing from the bucket"));

        ArtifactVerificationException thrown = assertThrows(ArtifactVerificationException.class,
                () -> service.restore(job(), clone.toFile(), scratch.toFile()));

        assertTrue(thrown.getMessage().contains("missing"));
    }

    @Test
    void aStorageOutageIsNotMistakenForNoArtifacts(@TempDir Path clone, @TempDir Path scratch) {
        when(terraformState.downloadArtifacts(eq("org"), eq("ws"), eq("7"), any(File.class)))
                .thenThrow(new TerraformStateUnavailableException("aws artifacts-read failed after 3 attempts", new RuntimeException("503")));

        assertThrows(TerraformStateUnavailableException.class, () -> service.restore(job(), clone.toFile(), scratch.toFile()));
    }

    @Test
    void theScratchDirectoryIsCreatedWhenItDoesNotExist(@TempDir Path base, @TempDir Path clone) {
        File scratch = new File(base.toFile(), "not/yet/there");
        when(terraformState.downloadArtifacts(eq("org"), eq("ws"), eq("7"), any(File.class))).thenReturn(null);

        assertFalse(service.restore(job(), clone.toFile(), scratch).restored());

        assertTrue(scratch.isDirectory());
    }
}
