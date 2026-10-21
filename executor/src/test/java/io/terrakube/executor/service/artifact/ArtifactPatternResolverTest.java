package io.terrakube.executor.service.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtifactPatternResolverTest {

    private final ArtifactPatternResolver resolver = new ArtifactPatternResolver();

    /** The globs of the resolved patterns; whether each is optional has its own tests. */
    private List<String> globs(List<String> patterns, Map<String, String> env) {
        return resolver.resolve(patterns, env).stream().map(ArtifactPatternResolver.ArtifactPattern::glob).toList();
    }

    @Test
    void literalPatternPassesThroughUnchanged() {
        assertEquals(List.of("build/**"), globs(List.of("build/**"), Map.of()));
    }

    @Test
    void placeholderIsSubstitutedFromEnvironmentVariables() {
        assertEquals(List.of("build/**"), globs(List.of("${ARTIFACT_PATH}/**"), Map.of("ARTIFACT_PATH", "build")));
    }

    @Test
    void unsetPlaceholderContributesNoPatterns() {
        assertEquals(List.of(), globs(List.of("${ARTIFACT_PATH}/**"), Map.of()));
    }

    @Test
    void emptyPlaceholderValueContributesNoPatterns() {
        // "${DIR}/**" with DIR="" would become "/**", which is not what anyone meant
        assertEquals(List.of(), globs(List.of("${ARTIFACT_PATH}/**"), Map.of("ARTIFACT_PATH", "")));
        assertEquals(List.of(), globs(List.of("${ARTIFACT_PATH}/**"), Map.of("ARTIFACT_PATH", "  ")));
    }

    @Test
    void placeholderExpandsIntoWorkspaceControlledCommaList() {
        assertEquals(List.of("build/**", "dist/**", "lambda-out/**"),
                globs(List.of("${ARTIFACT_PATHS}"), Map.of("ARTIFACT_PATHS", "build/**, dist/** ,lambda-out/**")));
    }

    @Test
    void mixOfFixedAndTemplatedPatternsBothResolve() {
        assertEquals(List.of("build/forwarder.zip", "static/**"),
                globs(List.of("${LAMBDA_ZIP_PATH}", "static/**"), Map.of("LAMBDA_ZIP_PATH", "build/forwarder.zip")));
    }

    @Test
    void anEntryHoldingSeveralPatternsOnSeparateLinesIsSplit() {
        // what the TERRAKUBE_PLAN_ARTIFACTS workspace variable looks like
        assertEquals(List.of("dist/*.zip", "layers/**", "extra/a.bin"),
                globs(List.of("dist/*.zip\nlayers/**\r\n\nextra/a.bin\n"), Map.of()));
    }

    @Test
    void commasInsideBracesDoNotSplitAGlob() {
        assertEquals(List.of("build/*.{zip,tar}", "dist/**"),
                globs(List.of("build/*.{zip,tar}, dist/**"), Map.of()));
        assertEquals(List.of("build/{a,{b,c}}/*.zip"),
                globs(List.of("build/{a,{b,c}}/*.zip"), Map.of()));
    }

    @Test
    void blankEntriesAndNullAreIgnored() {
        assertEquals(List.of(), globs(List.of("", "  ", ","), null));
    }

    @Test
    void theOptionalPrefixMarksAPatternThatMayMatchNothing() {
        List<ArtifactPatternResolver.ArtifactPattern> resolved = resolver.resolve(
                List.of("build/*.zip", "optional:extras/**", "optional: layers/**"), Map.of());

        assertEquals(List.of(
                new ArtifactPatternResolver.ArtifactPattern("build/*.zip", false),
                new ArtifactPatternResolver.ArtifactPattern("extras/**", true),
                new ArtifactPatternResolver.ArtifactPattern("layers/**", true)), resolved);
    }

    @Test
    void theOptionalPrefixWorksAfterSubstitutionAndInAWorkspaceVariableList() {
        assertEquals(List.of(new ArtifactPatternResolver.ArtifactPattern("dist/**", true)),
                resolver.resolve(List.of("optional:${DIR}/**"), Map.of("DIR", "dist")));
        assertEquals(List.of(new ArtifactPatternResolver.ArtifactPattern("a/**", false), new ArtifactPatternResolver.ArtifactPattern("b/**", true)),
                resolver.resolve(List.of("a/**\noptional:b/**"), Map.of()));
    }

    @Test
    void aBarePrefixWithNothingAfterItIsIgnored() {
        assertEquals(List.of(), resolver.resolve(List.of("optional:"), Map.of()));
    }
}
