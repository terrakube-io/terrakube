package io.terrakube.api.plugin.vcs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepoUrlNormalizerTest {

    @Test
    void trailingSlashRemoved() {
        assertThat(RepoUrlNormalizer.normalize("https://github.com/org/repo/"))
                .isEqualTo("https://github.com/org/repo");
    }

    @Test
    void gitSuffixStripped() {
        assertThat(RepoUrlNormalizer.normalize("https://github.com/org/repo.git"))
                .isEqualTo("https://github.com/org/repo");
    }

    @Test
    void mixedCaseLowered() {
        assertThat(RepoUrlNormalizer.normalize("https://GitHub.Com/Org/Repo"))
                .isEqualTo("https://github.com/org/repo");
    }

    @Test
    void nullReturnsNull() {
        assertThat(RepoUrlNormalizer.normalize(null)).isNull();
    }

    @Test
    void alreadyNormalizedReturnsSame() {
        assertThat(RepoUrlNormalizer.normalize("https://github.com/org/repo"))
                .isEqualTo("https://github.com/org/repo");
    }

    @Test
    void httpsUrlNormalized() {
        assertThat(RepoUrlNormalizer.normalize("HTTPS://GITHUB.COM/ORG/REPO.GIT"))
                .isEqualTo("https://github.com/org/repo");
    }

    @Test
    void httpUrlNormalized() {
        assertThat(RepoUrlNormalizer.normalize("HTTP://github.com/org/repo.git"))
                .isEqualTo("http://github.com/org/repo");
    }

    @Test
    void nestedPathsPreserved() {
        assertThat(RepoUrlNormalizer.normalize("https://gitlab.com/group/subgroup/repo"))
                .isEqualTo("https://gitlab.com/group/subgroup/repo");
    }

    @Test
    void trailingSlashAndGitCombined() {
        assertThat(RepoUrlNormalizer.normalize("https://github.com/org/repo.git/"))
                .isEqualTo("https://github.com/org/repo");
    }

    @Test
    void whitespaceTrimmed() {
        assertThat(RepoUrlNormalizer.normalize("  https://github.com/org/repo  "))
                .isEqualTo("https://github.com/org/repo");
    }
}
