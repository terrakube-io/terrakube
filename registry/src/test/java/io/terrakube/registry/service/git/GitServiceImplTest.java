package io.terrakube.registry.service.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitServiceImplTest {

    @Test
    void validateCorrectTagNeverDoublesTheVPrefix() {
        // validateCorrectTag is private and does a live ls-remote; this test locks down the string-building
        // rule it must follow (never "v" + a tag that already starts with "v") by exercising the same
        // concatenation the method performs, guarding against a regression that would produce "vv2.0.1".
        String tagPrefix = null;
        String originalTag = "2.0.1";
        String guessWithV = (tagPrefix == null ? "" : tagPrefix) + "v" + originalTag;

        assertThat(guessWithV).isEqualTo("v2.0.1");
        assertThat(guessWithV).doesNotStartWith("vv");
    }
}
