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

import io.terrakube.executor.service.artifact.ArtifactPatternResolver.ArtifactPattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactGlobMatcherTest {

    private final ArtifactGlobMatcher matcher = new ArtifactGlobMatcher();

    private static List<ArtifactPattern> required(String... globs) {
        return java.util.Arrays.stream(globs).map(glob -> new ArtifactPattern(glob, false)).toList();
    }

    @Test
    void matchesNestedFilesUnderGlobStarStar(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/output.zip", "zip");
        write(workingDirectory, "build/sub/nested.txt", "nested");
        write(workingDirectory, "README.md", "readme");

        assertEquals(2, matcher.match(workingDirectory, required("build/**")).files().size());
    }

    @Test
    void aSingleStarStaysInsideOneDirectory(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/a.zip", "a");
        write(workingDirectory, "build/deep/b.zip", "b");

        List<File> matched = matcher.match(workingDirectory, required("build/*.zip")).files();

        assertEquals(1, matched.size());
        assertEquals("a.zip", matched.get(0).getName());
    }

    @Test
    void braceAlternativesWork(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/a.zip", "a");
        write(workingDirectory, "build/b.tar", "b");
        write(workingDirectory, "build/c.txt", "c");

        assertEquals(2, matcher.match(workingDirectory, required("build/*.{zip,tar}")).files().size());
    }

    @Test
    void excludesPathsTerrakubeOwnsEvenWhenGlobWouldMatch(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/output.zip", "zip");
        write(workingDirectory, ".terraform/plugin.bin", "plugin");
        write(workingDirectory, ".git/config", "git");
        write(workingDirectory, "aws_backend_override.tf", "backend");
        write(workingDirectory, "terrakube_config_dynamic_credentials_aws.txt", "creds");
        write(workingDirectory, "terraformLibrary.tfPlan", "plan");
        write(workingDirectory, "build/.terraform/inner.bin", "nested plugin dir");

        List<File> matched = matcher.match(workingDirectory, required("**")).files();

        assertEquals(1, matched.size());
        assertEquals("output.zip", matched.get(0).getName());
    }

    @Test
    void theRawPlanAndThePolicyInputsAreNeverPackagedByABroadGlob(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/settings.json", "ok");
        write(workingDirectory, "plan.json", "{\"password\":\"hunter2\"}");
        write(workingDirectory, ".terrakube-policies/inputs-1.json", "{\"password\":\"hunter2\"}");
        write(workingDirectory, "terraformContent.tar.gz", "upload");

        for (String pattern : List.of("**", "*.json", "**/*.json", "**/*.gz")) {
            List<String> names = matcher.match(workingDirectory, required(pattern)).files().stream().map(File::getName).toList();

            assertFalse(names.contains("plan.json"), pattern);
            assertFalse(names.contains("inputs-1.json"), pattern);
            assertFalse(names.contains("terraformContent.tar.gz"), pattern);
        }
        assertEquals(List.of("settings.json"), matcher.match(workingDirectory, required("**/*.json")).files().stream().map(File::getName).toList());
    }

    @Test
    void noMatchesReturnsAnEmptyResult(@TempDir Path dir) throws IOException {
        ArtifactGlobMatcher.Matches matches = matcher.match(dir.toFile(), required("nonexistent/**"));

        assertTrue(matches.files().isEmpty());
        assertTrue(matches.skippedLinks().isEmpty());
    }

    @Test
    void aSymbolicLinkIsNeverFollowedOrPackagedButIsReported(@TempDir Path dir, @TempDir Path outside) throws IOException {
        File workingDirectory = dir.toFile();
        File secret = new File(outside.toFile(), "secret.txt");
        FileUtils.writeStringToFile(secret, "secret", Charset.defaultCharset());
        write(workingDirectory, "build/real.zip", "zip");
        Files.createSymbolicLink(new File(workingDirectory, "build/escape.txt").toPath(), secret.toPath());
        Files.createSymbolicLink(new File(workingDirectory, "build/linked-dir").toPath(), outside);

        ArtifactGlobMatcher.Matches matches = matcher.match(workingDirectory, required("build/**"));

        assertEquals(List.of("real.zip"), matches.files().stream().map(File::getName).toList());
        assertEquals(2, matches.skippedLinks().size());
        assertTrue(matches.skippedLinks().contains("build/escape.txt"));
    }

    @Test
    void anAbsoluteOrParentRelativePatternIsRefused(@TempDir Path dir) {
        File workingDirectory = dir.toFile();

        assertThrows(IllegalArgumentException.class, () -> matcher.match(workingDirectory, required("/etc/**")));
        assertThrows(IllegalArgumentException.class, () -> matcher.match(workingDirectory, required("../other/**")));
        assertThrows(IllegalArgumentException.class, () -> matcher.match(workingDirectory, required("build/../../x")));
        assertThrows(IllegalArgumentException.class, () -> matcher.match(workingDirectory, required("C:\\temp\\**")));
        // a parent segment at the end, or with a backslash, is refused as well
        assertThrows(IllegalArgumentException.class, () -> matcher.match(workingDirectory, required("build/..")));
        assertThrows(IllegalArgumentException.class, () -> matcher.match(workingDirectory, required("build\\..\\x")));
        assertThrows(IllegalArgumentException.class, () -> matcher.match(workingDirectory, required("..")));
    }

    @Test
    void aFileNameWithTwoDotsIsNotAParentSegment(@TempDir Path dir) throws IOException {
        File workingDirectory = dir.toFile();
        write(workingDirectory, "build/handler..v2.zip", "zip");

        assertEquals(1, matcher.match(workingDirectory, required("build/handler..v2.zip")).files().size());
    }

    @Test
    void anInvalidGlobIsReportedWithThePatternInTheMessage(@TempDir Path dir) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> matcher.match(dir.toFile(), required("build/{a,b")));

        assertTrue(thrown.getMessage().contains("build/{a,b"));
    }

    private void write(File baseDir, String relativePath, String content) throws IOException {
        FileUtils.writeStringToFile(new File(baseDir, relativePath), content, Charset.defaultCharset());
    }

    @Test
    void everyPatternThatMatchedNoFileIsReported(@TempDir Path dir) throws IOException {
        write(dir.toFile(), "build/a.zip", "a");

        ArtifactGlobMatcher.Matches matches = matcher.match(dir.toFile(),
                List.of(new ArtifactPattern("build/*.zip", false), new ArtifactPattern("dist/*.zip", false), new ArtifactPattern("x/**", true)));

        assertEquals(1, matches.files().size());
        assertEquals(List.of("dist/*.zip", "x/**"), matches.unmatched().stream().map(ArtifactPattern::glob).toList());
    }

    @Test
    void aPatternThatOnlyMatchesALinkOrAnExcludedFileCountsAsUnmatched(@TempDir Path dir, @TempDir Path outside) throws IOException {
        File target = new File(outside.toFile(), "t.zip");
        FileUtils.writeStringToFile(target, "t", Charset.defaultCharset());
        Files.createDirectories(dir.resolve("build"));
        Files.createSymbolicLink(dir.resolve("build/link.zip"), target.toPath());
        write(dir.toFile(), ".terraform/dev.zip", "inside .terraform");

        ArtifactGlobMatcher.Matches matches = matcher.match(dir.toFile(), required("build/*.zip", ".terraform/*.zip"));

        assertTrue(matches.files().isEmpty());
        assertEquals(2, matches.unmatched().size());
        assertEquals(List.of("build/link.zip"), matches.skippedLinks());
    }

    @Test
    void aPatternThatNamesAPlaceTerrakubeNeverPackagesGetsAnExplanation() {
        String reason = ArtifactGlobMatcher.neverPackagedReason(".terraform/development-aws-cost-usage-report.zip");

        assertTrue(reason.contains(".terraform"));
        assertTrue(reason.contains("build/"));
        assertTrue(ArtifactGlobMatcher.neverPackagedReason("./.terraform/*.zip") != null);
        assertEquals(null, ArtifactGlobMatcher.neverPackagedReason("build/*.zip"));
        assertEquals(null, ArtifactGlobMatcher.neverPackagedReason("**/*.zip"));
    }
}
