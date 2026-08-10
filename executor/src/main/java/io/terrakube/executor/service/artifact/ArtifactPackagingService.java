package io.terrakube.executor.service.artifact;

import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ArtifactPackagingService {

    public static final String ARTIFACTS_FILE_NAME = "plan-artifacts.tar.gz";

    private final ArtifactPatternResolver patternResolver;
    private final ArtifactGlobMatcher globMatcher;
    private final TarGzArchiver tarGzArchiver;

    public ArtifactPackagingService(ArtifactPatternResolver patternResolver, ArtifactGlobMatcher globMatcher,
                                     TarGzArchiver tarGzArchiver) {
        this.patternResolver = patternResolver;
        this.globMatcher = globMatcher;
        this.tarGzArchiver = tarGzArchiver;
    }

    public Optional<String> packageArtifacts(TerraformJob terraformJob, File workingDirectory) throws IOException {
        List<String> declaredPatterns = terraformJob.getArtifactPatterns();
        if (declaredPatterns == null || declaredPatterns.isEmpty()) {
            return Optional.empty();
        }

        List<String> resolvedPatterns = patternResolver.resolve(declaredPatterns, terraformJob.getEnvironmentVariables());
        if (resolvedPatterns.isEmpty()) {
            log.info("Artifact patterns declared but resolved to nothing for job {} step {}",
                    terraformJob.getJobId(), terraformJob.getStepId());
            return Optional.empty();
        }

        List<File> matchedFiles = globMatcher.match(workingDirectory, resolvedPatterns);
        if (matchedFiles.isEmpty()) {
            log.info("Artifact patterns resolved but matched no files for job {} step {}",
                    terraformJob.getJobId(), terraformJob.getStepId());
            return Optional.empty();
        }

        File archive = new File(workingDirectory, ARTIFACTS_FILE_NAME);
        tarGzArchiver.create(archive, workingDirectory, matchedFiles);
        String checksum = DigestUtils.sha256Hex(FileUtils.readFileToByteArray(archive));
        log.info("Packaged {} artifact file(s) for job {} step {} into {} (sha256 {})",
                matchedFiles.size(), terraformJob.getJobId(), terraformJob.getStepId(), archive.getName(), checksum);
        return Optional.of(checksum);
    }
}
