package io.terrakube.executor.service.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtifactPatternResolverTest {

    private final ArtifactPatternResolver resolver = new ArtifactPatternResolver();

    @Test
    void literalPatternPassesThroughUnchanged() {
        List<String> result = resolver.resolve(List.of("build/**"), Map.of());

        assertEquals(List.of("build/**"), result);
    }

    @Test
    void placeholderIsSubstitutedFromEnvironmentVariables() {
        List<String> result = resolver.resolve(
                List.of("${ARTIFACT_PATH}/**"),
                Map.of("ARTIFACT_PATH", "build"));

        assertEquals(List.of("build/**"), result);
    }

    @Test
    void unsetPlaceholderContributesNoPatterns() {
        List<String> result = resolver.resolve(List.of("${ARTIFACT_PATH}/**"), Map.of());

        assertEquals(List.of(), result);
    }

    @Test
    void placeholderExpandsIntoWorkspaceControlledCommaList() {
        List<String> result = resolver.resolve(
                List.of("${ARTIFACT_PATHS}"),
                Map.of("ARTIFACT_PATHS", "build/**, dist/** ,lambda-out/**"));

        assertEquals(List.of("build/**", "dist/**", "lambda-out/**"), result);
    }

    @Test
    void mixOfFixedAndTemplatedPatternsBothResolve() {
        List<String> result = resolver.resolve(
                List.of("${LAMBDA_ZIP_PATH}", "static/**"),
                Map.of("LAMBDA_ZIP_PATH", "build/forwarder.zip"));

        assertEquals(List.of("build/forwarder.zip", "static/**"), result);
    }
}
