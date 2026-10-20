package io.terrakube.executor.service.artifact;

import io.terrakube.executor.configuration.ArtifactProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPackagingServiceTest {

    private final ArtifactProperties properties = new ArtifactProperties();
    private final ArtifactPackagingService service = new ArtifactPackagingService(
            new ArtifactPatternResolver(), new ArtifactGlobMatcher(), new TarGzArchiver(), properties);

    private TerraformJob job(List<String> patterns, String... env) {
        TerraformJob job = new TerraformJob();
        job.setJobId("1");
        job.setStepId("2");
        job.setArtifactPatterns(patterns);
        HashMap<String, String> variables = new HashMap<>();
        for (int i = 0; i < env.length; i += 2) {
            variables.put(env[i], env[i + 1]);
        }
        job.setEnvironmentVariables(variables);
        return job;
    }

    @Test
    void noPatternsDeclaredIsNotConfigured(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        assertFalse(ArtifactPackagingService.isConfigured(job(null)));
        assertFalse(ArtifactPackagingService.isConfigured(job(List.of())));

        ArtifactPackagingService.Result result = service.packageArtifacts(job(null), dir.toFile(), new File(scratch.toFile(), "b.tar.gz"));

        assertEquals(ArtifactPackagingService.Status.NOT_CONFIGURED, result.status());
    }

    @Test
    void patternsThatReferenceUnsetVariablesAreReportedAsUnresolved(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        ArtifactPackagingService.Result result = service.packageArtifacts(job(List.of("${MISSING}/**")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz"));

        assertEquals(ArtifactPackagingService.Status.NO_PATTERNS_RESOLVED, result.status());
    }

    @Test
    void aPatternThatMatchesNothingFailsTheStepBecauseTheApplyWouldFailHalfwayThrough(@TempDir Path dir, @TempDir Path scratch) {
        File bundle = new File(scratch.toFile(), "b.tar.gz");

        IOException thrown = assertThrows(IOException.class,
                () -> service.packageArtifacts(job(List.of("build/*.zip")), dir.toFile(), bundle));

        assertTrue(thrown.getMessage().contains("\"build/*.zip\" matched no file"));
        assertTrue(thrown.getMessage().contains("optional:"));
        assertFalse(bundle.exists());
    }

    @Test
    void everyUnmatchedRequiredPatternIsNamedEvenWhenOthersMatched(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/a.zip"), "a", Charset.defaultCharset());

        IOException thrown = assertThrows(IOException.class, () -> service.packageArtifacts(
                job(List.of("build/*.zip", "dist/*.zip", "layers/**")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz")));

        assertTrue(thrown.getMessage().contains("\"dist/*.zip\", \"layers/**\" matched no files"));
        assertFalse(thrown.getMessage().contains("\"build/*.zip\""));
    }

    @Test
    void aPatternPointingInsideDotTerraformSaysWhyItCanNeverMatch(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        // the real incident: the archive provider wrote the zip to ./.terraform/<stage>-report.zip
        FileUtils.writeStringToFile(new File(dir.toFile(), ".terraform/development-report.zip"), "zip", Charset.defaultCharset());

        IOException thrown = assertThrows(IOException.class, () -> service.packageArtifacts(
                job(List.of(".terraform/*.zip")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz")));

        assertTrue(thrown.getMessage().contains("\".terraform\" is never packaged"));
        assertTrue(thrown.getMessage().contains("build/"));
    }

    @Test
    void aPatternThatOnlyMatchesALinkSaysSo(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(dir.toFile(), "real.zip"), "zip", Charset.defaultCharset());
        Files.createDirectories(dir.resolve("build"));
        Files.createSymbolicLink(dir.resolve("build/link.zip"), dir.resolve("real.zip"));

        IOException thrown = assertThrows(IOException.class, () -> service.packageArtifacts(
                job(List.of("build/*.zip")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz")));

        assertTrue(thrown.getMessage().contains("Symbolic links are never packaged"));
        assertTrue(thrown.getMessage().contains("build/link.zip"));
    }

    @Test
    void anOptionalPatternMayMatchNothing(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        File bundle = new File(scratch.toFile(), "b.tar.gz");

        ArtifactPackagingService.Result result = service.packageArtifacts(job(List.of("optional:build/**")), dir.toFile(), bundle);

        assertEquals(ArtifactPackagingService.Status.NO_FILES_MATCHED, result.status());
        assertEquals(List.of("optional:build/**"), result.patterns());
        assertFalse(bundle.exists());
    }

    @Test
    void anOptionalPatternThatMatchesNothingDoesNotStopTheOthersFromBeingPackaged(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/a.zip"), "a", Charset.defaultCharset());

        ArtifactPackagingService.Result result = service.packageArtifacts(
                job(List.of("build/*.zip", "optional:layers/**")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz"));

        assertEquals(ArtifactPackagingService.Status.PACKAGED, result.status());
        assertEquals(List.of("build/a.zip"), result.fileNames());
    }

    @Test
    void matchedFilesArePackagedWithTheirChecksumAndSize(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        File workingDirectory = dir.toFile();
        FileUtils.writeStringToFile(new File(workingDirectory, "build/output.zip"), "zip-bytes", Charset.defaultCharset());
        File bundle = new File(scratch.toFile(), "b.tar.gz");

        ArtifactPackagingService.Result result = service.packageArtifacts(
                job(List.of("${ARTIFACT_PATH}/**"), "ARTIFACT_PATH", "build"), workingDirectory, bundle);

        assertEquals(ArtifactPackagingService.Status.PACKAGED, result.status());
        assertEquals(1, result.files());
        assertEquals(bundle.length(), result.bytes());
        assertEquals(DigestUtils.sha256Hex(Files.readAllBytes(bundle.toPath())), result.sha256());
        assertEquals(List.of("build/output.zip"), result.fileNames());
    }

    @Test
    void theBundleIsNotPartOfWhatItPackages(@TempDir Path dir) throws Exception {
        // a "**" pattern must not pick up a bundle that sits in the working directory
        File workingDirectory = dir.toFile();
        FileUtils.writeStringToFile(new File(workingDirectory, "a.txt"), "a", Charset.defaultCharset());
        File bundle = new File(workingDirectory.getParentFile(), "outside-" + System.nanoTime() + ".tar.gz");
        try {
            ArtifactPackagingService.Result result = service.packageArtifacts(job(List.of("**")), workingDirectory, bundle);
            assertEquals(1, result.files());
        } finally {
            bundle.delete();
        }
    }

    @Test
    void symbolicLinksAreReportedNotPackaged(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        File workingDirectory = dir.toFile();
        FileUtils.writeStringToFile(new File(workingDirectory, "build/real.zip"), "zip", Charset.defaultCharset());
        Files.createSymbolicLink(new File(workingDirectory, "build/link.zip").toPath(), new File(workingDirectory, "build/real.zip").toPath());

        ArtifactPackagingService.Result result = service.packageArtifacts(job(List.of("build/**")), workingDirectory, new File(scratch.toFile(), "b.tar.gz"));

        assertEquals(1, result.files());
        assertEquals(List.of("build/link.zip"), result.skippedLinks());
    }

    @Test
    void tooManyFilesAreRefusedWithAnActionableMessage(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        properties.setMaxFiles(2);
        for (int i = 0; i < 3; i++) {
            FileUtils.writeStringToFile(new File(dir.toFile(), "build/f" + i), "x", Charset.defaultCharset());
        }

        IOException thrown = assertThrows(IOException.class,
                () -> service.packageArtifacts(job(List.of("build/**")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz")));

        assertTrue(thrown.getMessage().contains("3 files"));
        assertTrue(thrown.getMessage().contains("io.terrakube.executor.artifacts.max-files"));
    }

    @Test
    void exactlyTheFileLimitIsAccepted(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        properties.setMaxFiles(2);
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/a"), "x", Charset.defaultCharset());
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/b"), "x", Charset.defaultCharset());

        ArtifactPackagingService.Result result = service.packageArtifacts(job(List.of("build/**")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz"));

        assertEquals(2, result.files());
    }

    @Test
    void tooManyBytesAreRefusedBeforeAnythingIsArchived(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        properties.setMaxBytes(10);
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/a"), "123456", Charset.defaultCharset());
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/b"), "123456", Charset.defaultCharset());
        File bundle = new File(scratch.toFile(), "b.tar.gz");

        IOException thrown = assertThrows(IOException.class,
                () -> service.packageArtifacts(job(List.of("build/**")), dir.toFile(), bundle));

        assertTrue(thrown.getMessage().contains("io.terrakube.executor.artifacts.max-bytes"));
        assertFalse(bundle.exists());
    }

    @Test
    void exactlyTheByteLimitIsAccepted(@TempDir Path dir, @TempDir Path scratch) throws Exception {
        properties.setMaxBytes(12);
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/a"), "123456", Charset.defaultCharset());
        FileUtils.writeStringToFile(new File(dir.toFile(), "build/b"), "123456", Charset.defaultCharset());

        assertEquals(ArtifactPackagingService.Status.PACKAGED,
                service.packageArtifacts(job(List.of("build/**")), dir.toFile(), new File(scratch.toFile(), "b.tar.gz")).status());
    }

    @Test
    void aBadPatternIsRefusedWithoutWritingAnything(@TempDir Path dir, @TempDir Path scratch) {
        File bundle = new File(scratch.toFile(), "b.tar.gz");

        assertThrows(IllegalArgumentException.class, () -> service.packageArtifacts(job(List.of("/etc/**")), dir.toFile(), bundle));
        assertFalse(bundle.exists());
    }
}
