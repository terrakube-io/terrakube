package io.terrakube.executor.service.artifact;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class ArtifactGlobMatcher {

    private static final Set<String> EXCLUDED_NAMES = Set.of(
            ".git", ".terraform", ".ssh", ".sshModule",
            "terrakube_config_dynamic_credentials_aws.txt",
            "terrakube_dynamic_credentials.json",
            "terrakube_config_dynamic_credentials.json",
            ".terrakube_temp_env", "commitHash.info");

    private static final Pattern BACKEND_OVERRIDE_PATTERN = Pattern.compile(".*_override\\.tf$");

    public List<File> match(File workingDirectory, List<String> resolvedPatterns) throws IOException {
        Path root = workingDirectory.toPath().toAbsolutePath().normalize();
        FileSystem fileSystem = FileSystems.getDefault();
        List<PathMatcher> matchers = resolvedPatterns.stream()
                .map(pattern -> fileSystem.getPathMatcher("glob:" + pattern))
                .toList();

        List<File> matched = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path path : files) {
                Path relative = root.relativize(path);
                if (isExcluded(relative)) {
                    continue;
                }
                if (matchesAny(matchers, relative)) {
                    Path resolved = path.toRealPath();
                    if (!resolved.startsWith(root)) {
                        throw new IOException("Artifact pattern resolved outside the working directory: " + relative);
                    }
                    matched.add(resolved.toFile());
                }
            }
        }
        return matched;
    }

    private boolean matchesAny(List<PathMatcher> matchers, Path relative) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative)) {
                return true;
            }
        }
        return false;
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
