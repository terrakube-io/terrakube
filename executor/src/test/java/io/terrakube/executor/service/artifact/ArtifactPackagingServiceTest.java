package io.terrakube.executor.service.artifact;

import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPackagingServiceTest {

    private final ArtifactPatternResolver patternResolver = new ArtifactPatternResolver();
    private final ArtifactGlobMatcher globMatcher = new ArtifactGlobMatcher();
    private final TarGzArchiver tarGzArchiver = new TarGzArchiver();
    private final ArtifactPackagingService service =
            new ArtifactPackagingService(patternResolver, globMatcher, tarGzArchiver);

    @Test
    void noPatternsDeclaredReturnsEmpty(@TempDir Path dir) throws Exception {
        TerraformJob job = new TerraformJob();
        job.setEnvironmentVariables(new HashMap<>());

        Optional<String> result = service.packageArtifacts(job, dir.toFile());

        assertTrue(result.isEmpty());
    }

    @Test
    void patternsResolveToNoMatchesReturnsEmpty(@TempDir Path dir) throws Exception {
        TerraformJob job = new TerraformJob();
        job.setArtifactPatterns(List.of("build/**"));
        job.setEnvironmentVariables(new HashMap<>());

        Optional<String> result = service.packageArtifacts(job, dir.toFile());

        assertTrue(result.isEmpty());
    }

    @Test
    void matchedFilesArePackagedWithChecksum(@TempDir Path dir) throws Exception {
        File workingDirectory = dir.toFile();
        File output = new File(workingDirectory, "build/output.zip");
        FileUtils.writeStringToFile(output, "zip-bytes", Charset.defaultCharset());

        TerraformJob job = new TerraformJob();
        job.setArtifactPatterns(List.of("${ARTIFACT_PATH}/**"));
        HashMap<String, String> env = new HashMap<>();
        env.put("ARTIFACT_PATH", "build");
        job.setEnvironmentVariables(env);

        Optional<String> result = service.packageArtifacts(job, workingDirectory);

        assertTrue(result.isPresent());
        File archive = new File(workingDirectory, ArtifactPackagingService.ARTIFACTS_FILE_NAME);
        assertTrue(archive.exists());
        assertEquals(DigestUtils.sha256Hex(FileUtils.readFileToByteArray(archive)), result.get());
    }
}
