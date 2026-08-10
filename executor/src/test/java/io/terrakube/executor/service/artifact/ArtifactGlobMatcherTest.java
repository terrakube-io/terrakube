package io.terrakube.executor.service.artifact;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactGlobMatcherTest {

    private final ArtifactGlobMatcher matcher = new ArtifactGlobMatcher();

    @Test
    void matchesNestedFilesUnderGlobStarStar(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/output.zip", "zip");
        write(workingDirectory, "build/sub/nested.txt", "nested");
        write(workingDirectory, "README.md", "readme");

        List<File> matched = matcher.match(workingDirectory, List.of("build/**"));

        assertEquals(2, matched.size());
    }

    @Test
    void excludesSensitivePathsEvenWhenGlobWouldMatch(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/output.zip", "zip");
        write(workingDirectory, ".terraform/plugin.bin", "plugin");
        write(workingDirectory, "aws_backend_override.tf", "backend");
        write(workingDirectory, "terrakube_config_dynamic_credentials_aws.txt", "creds");

        List<File> matched = matcher.match(workingDirectory, List.of("**"));

        assertEquals(1, matched.size());
        assertEquals("output.zip", matched.get(0).getName());
    }

    @Test
    void noMatchesReturnsEmptyList(@TempDir Path dir) throws IOException {
        List<File> matched = matcher.match(dir.toFile(), List.of("nonexistent/**"));

        assertTrue(matched.isEmpty());
    }

    @Test
    void symlinkEscapingWorkingDirectoryThrows(@TempDir Path dir, @TempDir Path outside) throws IOException {
        File workingDirectory = dir.toFile();
        File secret = new File(outside.toFile(), "secret.txt");
        FileUtils.writeStringToFile(secret, "secret", Charset.defaultCharset());

        File linkDir = new File(workingDirectory, "build");
        linkDir.mkdirs();
        Files.createSymbolicLink(new File(linkDir, "escape.txt").toPath(), secret.toPath());

        IOException thrown = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> matcher.match(workingDirectory, List.of("build/**")));
        assertTrue(thrown.getMessage().contains("outside"));
    }

    private void write(File baseDir, String relativePath, String content) throws IOException {
        File file = new File(baseDir, relativePath);
        FileUtils.writeStringToFile(file, content, Charset.defaultCharset());
    }
}
