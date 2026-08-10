package io.terrakube.executor.service.artifact;

import io.terrakube.executor.configuration.ArtifactProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.TarGzArchiver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Packs the files a plan step produced (the template's or workspace's artifact globs) into one
 * bundle for the apply step. The bundle is built from, and hashed from, files on disk, never held
 * in memory, and is refused when it would exceed the configured size or file count.
 */
@Slf4j
@Service
public class ArtifactPackagingService {

    public enum Status {
        /** No artifact globs are declared for this step. */
        NOT_CONFIGURED,
        /** Globs are declared but every one referenced a variable that is not set. */
        NO_PATTERNS_RESOLVED,
        /** The globs matched no files. */
        NO_FILES_MATCHED,
        PACKAGED
    }

    /**
     * {@code bundle}, {@code sha256}, {@code bytes} and {@code files} are only set when {@link Status#PACKAGED}.
     * {@code skippedLinks} are matched symbolic links, which are never packaged.
     */
    public record Result(Status status, List<String> patterns, File bundle, String sha256, long bytes, int files,
                         List<String> skippedLinks, List<String> fileNames) {
        static Result of(Status status, List<String> patterns) {
            return new Result(status, patterns, null, null, 0, 0, List.of(), List.of());
        }
    }

    private final ArtifactPatternResolver patternResolver;
    private final ArtifactGlobMatcher globMatcher;
    private final TarGzArchiver tarGzArchiver;
    private final ArtifactProperties properties;

    public ArtifactPackagingService(ArtifactPatternResolver patternResolver, ArtifactGlobMatcher globMatcher,
                                    TarGzArchiver tarGzArchiver, ArtifactProperties properties) {
        this.patternResolver = patternResolver;
        this.globMatcher = globMatcher;
        this.tarGzArchiver = tarGzArchiver;
        this.properties = properties;
    }

    public static boolean isConfigured(TerraformJob terraformJob) {
        List<String> declared = terraformJob.getArtifactPatterns();
        return declared != null && !declared.isEmpty();
    }

    /**
     * @param bundleFile where to write the bundle; it must be outside {@code workingDirectory}.
     * @throws IllegalArgumentException when an artifact pattern is not a valid, relative glob.
     * @throws IOException               when the files cannot be read or exceed the configured limits.
     */
    public Result packageArtifacts(TerraformJob terraformJob, File workingDirectory, File bundleFile) throws IOException {
        if (!isConfigured(terraformJob)) {
            return Result.of(Status.NOT_CONFIGURED, List.of());
        }

        List<ArtifactPatternResolver.ArtifactPattern> patterns = patternResolver.resolve(terraformJob.getArtifactPatterns(), terraformJob.getEnvironmentVariables());
        List<String> resolvedPatterns = patterns.stream().map(ArtifactPatternResolver.ArtifactPattern::toString).toList();
        if (patterns.isEmpty()) {
            log.info("Artifact patterns declared but resolved to nothing for job {} step {}",
                    terraformJob.getJobId(), terraformJob.getStepId());
            return Result.of(Status.NO_PATTERNS_RESOLVED, resolvedPatterns);
        }

        ArtifactGlobMatcher.Matches matches = globMatcher.match(workingDirectory, patterns);
        failOnUnmatchedRequiredPatterns(matches, terraformJob);
        List<File> matchedFiles = matches.files();
        if (matchedFiles.isEmpty()) {
            log.info("Artifact patterns {} matched no files for job {} step {}",
                    resolvedPatterns, terraformJob.getJobId(), terraformJob.getStepId());
            return new Result(Status.NO_FILES_MATCHED, resolvedPatterns, null, null, 0, 0, matches.skippedLinks(), List.of());
        }
        enforceLimits(matchedFiles);

        tarGzArchiver.create(bundleFile, workingDirectory, matchedFiles);
        String checksum;
        try (InputStream in = new FileInputStream(bundleFile)) {
            checksum = DigestUtils.sha256Hex(in);
        }
        log.info("Packaged {} artifact file(s) for job {} step {} into {} bytes (sha256 {})",
                matchedFiles.size(), terraformJob.getJobId(), terraformJob.getStepId(), bundleFile.length(), checksum);
        Path base = workingDirectory.toPath().toAbsolutePath().normalize();
        List<String> names = matchedFiles.stream()
                .map(file -> base.relativize(file.toPath().toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/'))
                .sorted()
                .toList();
        return new Result(Status.PACKAGED, resolvedPatterns, bundleFile, checksum, bundleFile.length(), matchedFiles.size(), matches.skippedLinks(), names);
    }

    /**
     * A pattern someone wrote down is almost always a file the apply needs. Letting one match nothing
     * would let the plan succeed and the apply fail halfway through, after it had already created
     * resources, so it fails the plan. {@code optional:} opts a pattern out of this.
     */
    private void failOnUnmatchedRequiredPatterns(ArtifactGlobMatcher.Matches matches, TerraformJob terraformJob) throws IOException {
        List<ArtifactPatternResolver.ArtifactPattern> required = matches.unmatched().stream()
                .filter(pattern -> !pattern.optional())
                .toList();
        if (required.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("Plan artifacts pattern");
        message.append(required.size() > 1 ? "s " : " ");
        message.append(required.stream().map(pattern -> "\"" + pattern.glob() + "\"").collect(java.util.stream.Collectors.joining(", ")));
        message.append(required.size() > 1 ? " matched no files" : " matched no file");
        message.append(" in the working directory. Fix the pattern, or prefix it with \"")
                .append(ArtifactPatternResolver.OPTIONAL_PREFIX).append("\" if it may match nothing.");
        for (ArtifactPatternResolver.ArtifactPattern pattern : required) {
            String reason = ArtifactGlobMatcher.neverPackagedReason(pattern.glob());
            if (reason != null) {
                message.append(" ").append(reason).append(".");
            }
        }
        if (!matches.skippedLinks().isEmpty()) {
            message.append(" Symbolic links are never packaged, and these matched: ").append(matches.skippedLinks()).append(".");
        }
        log.warn("Job {} step {}: {}", terraformJob.getJobId(), terraformJob.getStepId(), message);
        throw new IOException(message.toString());
    }

    private void enforceLimits(List<File> files) throws IOException {
        if (files.size() > properties.getMaxFiles()) {
            throw new IOException("Plan artifacts match " + files.size() + " files, more than the limit of "
                    + properties.getMaxFiles() + " (io.terrakube.executor.artifacts.max-files). Narrow the artifact patterns.");
        }
        long total = 0;
        for (File file : files) {
            total += file.length();
            if (total > properties.getMaxBytes()) {
                throw new IOException("Plan artifacts are larger than the limit of " + properties.getMaxBytes()
                        + " bytes (io.terrakube.executor.artifacts.max-bytes). Narrow the artifact patterns.");
            }
        }
    }
}
