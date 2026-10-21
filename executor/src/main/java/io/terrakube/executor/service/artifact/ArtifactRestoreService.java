package io.terrakube.executor.service.artifact;

import io.terrakube.executor.configuration.ArtifactProperties;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts the files a plan step packaged back into the apply step's fresh clone before Terraform
 * applies the saved plan. The bundle is downloaded to disk, checked against the sha256 the plan step
 * recorded, and only then unpacked, within the configured size limits. Any problem fails the apply
 * rather than letting it run without the files the plan was made with.
 */
@Slf4j
@Service
public class ArtifactRestoreService {

    /** What was put back: the number of files and the sha256 of the bundle they came from. */
    public record Result(boolean restored, int files, String sha256) {
        public static final Result NOTHING = new Result(false, 0, null);
    }

    private final TerraformState terraformState;
    private final TarGzArchiver tarGzArchiver;
    private final ArtifactProperties properties;

    public ArtifactRestoreService(TerraformState terraformState, TarGzArchiver tarGzArchiver, ArtifactProperties properties) {
        this.terraformState = terraformState;
        this.tarGzArchiver = tarGzArchiver;
        this.properties = properties;
    }

    /**
     * @param scratchDirectory where the bundle is downloaded to; it must be outside {@code workingDirectory}.
     * @return what was restored, or {@link Result#NOTHING} when the job has no artifacts.
     * @throws ArtifactVerificationException when the bundle is missing, does not match its checksum, or cannot be unpacked.
     */
    public Result restore(TerraformJob terraformJob, File workingDirectory, File scratchDirectory) throws ArtifactVerificationException {
        Path bundle = null;
        try {
            Files.createDirectories(scratchDirectory.toPath());
            bundle = Files.createTempFile(scratchDirectory.toPath(), "plan-artifacts-", ".tar.gz");
            // The storage API refuses to write over an existing file.
            Files.delete(bundle);

            String expectedChecksum = terraformState.downloadArtifacts(terraformJob.getOrganizationId(),
                    terraformJob.getWorkspaceId(), terraformJob.getJobId(), bundle.toFile());
            if (expectedChecksum == null) {
                return Result.NOTHING;
            }
            if (expectedChecksum.isBlank()) {
                throw new ArtifactVerificationException("The plan step recorded plan artifacts without a checksum, so they cannot be verified");
            }

            String actualChecksum;
            try (InputStream in = Files.newInputStream(bundle)) {
                actualChecksum = DigestUtils.sha256Hex(in);
            }
            if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
                throw new ArtifactVerificationException("Plan artifacts checksum mismatch: expected " + expectedChecksum
                        + " but the downloaded bundle hashed to " + actualChecksum);
            }

            int files;
            try (InputStream in = Files.newInputStream(bundle)) {
                files = tarGzArchiver.extractBounded(in, workingDirectory.toPath(), properties.getMaxBytes(), properties.getMaxFiles());
            }
            log.info("Restored {} plan artifact file(s) for job {} step {} into {} (sha256 {})", files, terraformJob.getJobId(),
                    terraformJob.getStepId(), workingDirectory.getAbsolutePath(), actualChecksum);
            return new Result(true, files, actualChecksum);
        } catch (IOException e) {
            throw new ArtifactVerificationException("Failed to restore plan artifacts: " + e.getMessage());
        } finally {
            if (bundle != null) {
                try {
                    Files.deleteIfExists(bundle);
                } catch (IOException e) {
                    log.warn("Could not remove the downloaded plan artifacts bundle {}: {}", bundle, e.getMessage());
                }
            }
        }
    }
}
