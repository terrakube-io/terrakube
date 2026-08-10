package io.terrakube.api.plugin.scheduler.module;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleVersionNormalizerTest {

    @Test
    void leadingVIsStripped() {
        Optional<ModuleVersionNormalizer.NormalizedVersion> result =
                ModuleVersionNormalizer.normalize("v2.0.1", null);

        assertThat(result).isPresent();
        assertThat(result.get().canonicalVersion()).isEqualTo("2.0.1");
        assertThat(result.get().originalTag()).isEqualTo("v2.0.1");
    }

    @Test
    void unprefixedTagIsUnchanged() {
        Optional<ModuleVersionNormalizer.NormalizedVersion> result =
                ModuleVersionNormalizer.normalize("2.0.1", null);

        assertThat(result).isPresent();
        assertThat(result.get().canonicalVersion()).isEqualTo("2.0.1");
        assertThat(result.get().originalTag()).isEqualTo("2.0.1");
    }

    @Test
    void tagPrefixAndVAreBothStripped() {
        Optional<ModuleVersionNormalizer.NormalizedVersion> result =
                ModuleVersionNormalizer.normalize("module-v2.0.1", "module-");

        assertThat(result).isPresent();
        assertThat(result.get().canonicalVersion()).isEqualTo("2.0.1");
        assertThat(result.get().originalTag()).isEqualTo("module-v2.0.1");
    }

    @Test
    void prereleaseIsPreserved() {
        Optional<ModuleVersionNormalizer.NormalizedVersion> result =
                ModuleVersionNormalizer.normalize("2.0.1-rc.1", null);

        assertThat(result).isPresent();
        assertThat(result.get().canonicalVersion()).isEqualTo("2.0.1-rc.1");
    }

    @Test
    void buildMetadataIsPreserved() {
        Optional<ModuleVersionNormalizer.NormalizedVersion> result =
                ModuleVersionNormalizer.normalize("v2.0.1+build.5", null);

        assertThat(result).isPresent();
        assertThat(result.get().canonicalVersion()).isEqualTo("2.0.1+build.5");
    }

    @Test
    void majorOnlyTagIsNotPublished() {
        assertThat(ModuleVersionNormalizer.normalize("v2", null)).isEmpty();
    }

    @Test
    void nonSemverTagIsNotPublished() {
        assertThat(ModuleVersionNormalizer.normalize("latest", null)).isEmpty();
    }

    @Test
    void uppercaseVIsNotStripped() {
        assertThat(ModuleVersionNormalizer.normalize("V2.0.1", null)).isEmpty();
    }

    @Test
    void tagNotMatchingConfiguredPrefixIsSkipped() {
        assertThat(ModuleVersionNormalizer.normalize("v2.0.1", "module-")).isEmpty();
    }

    @Test
    void emptyTagPrefixBehavesLikeNoPrefix() {
        Optional<ModuleVersionNormalizer.NormalizedVersion> result =
                ModuleVersionNormalizer.normalize("v2.0.1", "");

        assertThat(result).isPresent();
        assertThat(result.get().canonicalVersion()).isEqualTo("2.0.1");
    }

    @Test
    void nullTagIsSkipped() {
        assertThat(ModuleVersionNormalizer.normalize(null, null)).isEmpty();
    }
}
