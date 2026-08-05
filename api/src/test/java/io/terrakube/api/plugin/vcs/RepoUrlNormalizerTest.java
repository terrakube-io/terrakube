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

    // Azure DevOps URL normalization tests

    @Test
    void azureDevOpsUserinfoStripped() {
        assertThat(RepoUrlNormalizer.normalize("https://org@dev.azure.com/org/proj/_git/repo"))
                .isEqualTo("https://dev.azure.com/org/proj/repo");
    }

    @Test
    void azureDevOpsGitSegmentRemoved() {
        assertThat(RepoUrlNormalizer.normalize("https://dev.azure.com/org/proj/_git/repo"))
                .isEqualTo("https://dev.azure.com/org/proj/repo");
    }

    @Test
    void azureDevOpsCombinedUserinfoGitSuffix() {
        assertThat(RepoUrlNormalizer.normalize("https://org@dev.azure.com/org/proj/_git/repo.git"))
                .isEqualTo("https://dev.azure.com/org/proj/repo");
    }

    @Test
    void azureDevOpsPlainUrlUnchanged() {
        assertThat(RepoUrlNormalizer.normalize("https://dev.azure.com/org/proj/repo"))
                .isEqualTo("https://dev.azure.com/org/proj/repo");
    }

    @Test
    void azureDevOpsVisualStudioGitSegmentRemoved() {
        assertThat(RepoUrlNormalizer.normalize("https://org.visualstudio.com/proj/_git/repo"))
                .isEqualTo("https://org.visualstudio.com/proj/repo");
    }

    @Test
    void azureDevOpsCoalesceDifferentForms() {
        String form1 = RepoUrlNormalizer.normalize("https://org@dev.azure.com/org/proj/_git/repo");
        String form2 = RepoUrlNormalizer.normalize("https://dev.azure.com/org/proj/repo");
        String form3 = RepoUrlNormalizer.normalize("https://dev.azure.com/org/proj/_git/repo.git");
        assertThat(form1).isEqualTo(form2).isEqualTo(form3);
    }
}
