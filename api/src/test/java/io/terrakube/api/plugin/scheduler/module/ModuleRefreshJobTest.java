package io.terrakube.api.plugin.scheduler.module;

import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModuleRefreshJobTest {

    private final Ref dummyRef = mock(Ref.class);

    @Test
    void publishesEachNonCollidingTagUnderItsCanonicalVersion() {
        Map<String, Ref> rawRepoTags = new HashMap<>();
        rawRepoTags.put("v2.0.1", dummyRef);
        rawRepoTags.put("2.0.2", dummyRef);
        rawRepoTags.put("latest", dummyRef);
        rawRepoTags.put("v2", dummyRef);

        Map<String, ModuleVersionNormalizer.NormalizedVersion> result =
                new ModuleRefreshJob().resolveCanonicalVersions(rawRepoTags, null, "test-module");

        assertThat(result).hasSize(2);
        assertThat(result.get("2.0.1").originalTag()).isEqualTo("v2.0.1");
        assertThat(result.get("2.0.2").originalTag()).isEqualTo("2.0.2");
    }

    @Test
    void collidingTagsAreExcludedFromTheResult() {
        Map<String, Ref> rawRepoTags = new HashMap<>();
        rawRepoTags.put("v2.0.1", dummyRef);
        rawRepoTags.put("2.0.1", dummyRef);
        rawRepoTags.put("2.0.2", dummyRef);

        Map<String, ModuleVersionNormalizer.NormalizedVersion> result =
                new ModuleRefreshJob().resolveCanonicalVersions(rawRepoTags, null, "test-module");

        assertThat(result).containsOnlyKeys("2.0.2");
    }

    @Test
    void tagPrefixIsRespected() {
        Map<String, Ref> rawRepoTags = new HashMap<>();
        rawRepoTags.put("module-v2.0.1", dummyRef);
        rawRepoTags.put("other-v9.9.9", dummyRef);

        Map<String, ModuleVersionNormalizer.NormalizedVersion> result =
                new ModuleRefreshJob().resolveCanonicalVersions(rawRepoTags, "module-", "test-module");

        assertThat(result).containsOnlyKeys("2.0.1");
    }
}
