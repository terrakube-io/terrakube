package io.terrakube.executor.service.artifact;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the regular files under a working directory that match the artifact globs. Globs are
 * relative to the working directory and follow {@code java.nio} glob rules ({@code *} stays inside a
 * directory, {@code **} crosses directories, so {@code build/**} is everything under {@code build}).
 * Symbolic links are never followed or packaged, and the directories and files Terrakube itself
 * creates in the clone (git data, the Terraform plugin cache, credentials) are never matched.
 */
@Component
public class ArtifactGlobMatcher {

    /**
     * The regular files that matched, the symbolic links that matched but are not packaged, and the
     * patterns that matched no regular file.
     */
    public record Matches(List<File> files, List<String> skippedLinks, List<ArtifactPatternResolver.ArtifactPattern> unmatched) {
    }

    private static final Set<String> EXCLUDED_NAMES = Set.of(
            ".git", ".terraform", ".ssh", ".sshModule",
            "terrakube_config_dynamic_credentials_aws.txt",
            "terrakube_dynamic_credentials.json",
            "terrakube_config_dynamic_credentials.json",
            ".terrakube_temp_env", "commitHash.info", "terrakube-run-variables.tfvars",
            "terraformLibrary.tfPlan",
            // plan.json is the raw plan (sensitive values included) and the policy inputs embed it;
            // the uploaded configuration archive is a copy of the source, not something a plan built.
            "plan.json", ".terrakube-policies", "terraformContent.tar.gz");

    private static final Pattern BACKEND_OVERRIDE_PATTERN = Pattern.compile(".*_override\\.tf$");

    /**
     * @throws IllegalArgumentException when a pattern is not a valid glob or is absolute.
     */
    public Matches match(File workingDirectory, List<ArtifactPatternResolver.ArtifactPattern> resolvedPatterns) throws IOException {
        Path root = workingDirectory.toPath().toAbsolutePath().normalize();
        List<PathMatcher> matchers = new ArrayList<>();
        for (ArtifactPatternResolver.ArtifactPattern pattern : resolvedPatterns) {
            matchers.add(compile(pattern.glob()));
        }
        boolean[] matchedAFile = new boolean[matchers.size()];

        List<File> matched = new ArrayList<>();
        List<String> skippedLinks = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Skip an excluded directory as a whole: walking a plugin cache or a git history
                // only to throw every file away is slow and, on a pod with limited disk, not free.
                return !dir.equals(root) && isExcluded(root.relativize(dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = root.relativize(file);
                if (isExcluded(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isSymbolicLink()) {
                    if (matchesAny(matchers, relative, null)) {
                        skippedLinks.add(relative.toString());
                    }
                } else if (attrs.isRegularFile() && matchesAny(matchers, relative, matchedAFile)) {
                    matched.add(file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        List<ArtifactPatternResolver.ArtifactPattern> unmatched = new ArrayList<>();
        for (int i = 0; i < matchedAFile.length; i++) {
            if (!matchedAFile[i]) {
                unmatched.add(resolvedPatterns.get(i));
            }
        }
        return new Matches(matched, skippedLinks, unmatched);
    }

    // A ".." path segment, with either separator, anywhere: "../x", "x/..", "x\..\y" and "..". A name that
    // only contains two dots, such as "a..b.zip", is a normal file name.
    private static boolean hasParentSegment(String pattern) {
        for (String segment : pattern.split("[/\\\\]")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private PathMatcher compile(String pattern) {
        if (pattern.startsWith("/") || pattern.matches("^[A-Za-z]:[\\\\/].*") || hasParentSegment(pattern)) {
            throw new IllegalArgumentException("Artifact pattern \"" + pattern
                    + "\" must be relative to the working directory and must not contain \"..\"");
        }
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Artifact pattern \"" + pattern + "\" is not a valid glob: " + e.getMessage(), e);
        }
    }

    /** Records, in {@code hits}, every pattern the path matches (not just the first). */
    private boolean matchesAny(List<PathMatcher> matchers, Path relative, boolean[] hits) {
        boolean any = false;
        for (int i = 0; i < matchers.size(); i++) {
            if (matchers.get(i).matches(relative)) {
                any = true;
                if (hits != null) {
                    hits[i] = true;
                }
            }
        }
        return any;
    }

    /**
     * Why a pattern can never match, when it names a place Terrakube does not package (for example a
     * file under {@code .terraform}); {@code null} when the pattern is simply unmatched.
     */
    public static String neverPackagedReason(String glob) {
        for (String segment : glob.split("/")) {
            if (EXCLUDED_NAMES.contains(segment)) {
                return "\"" + segment + "\" is never packaged (it holds Terraform's own working data or credentials). "
                        + "Write the file somewhere else, for example under build/";
            }
        }
        return null;
    }

    private boolean isExcluded(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (EXCLUDED_NAMES.contains(name) || BACKEND_OVERRIDE_PATTERN.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }
}
