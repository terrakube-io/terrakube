package io.terrakube.api.plugin.vcs;

import io.terrakube.api.rs.vcs.Vcs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VcsApiUrlValidatorTest {

    private static Vcs vcsWithApiUrl(String apiUrl) {
        Vcs vcs = new Vcs();
        vcs.setApiUrl(apiUrl);
        return vcs;
    }

    @Test
    void allowsExactOriginAndSubpathsForGithub() {
        Vcs vcs = vcsWithApiUrl("https://api.github.com");

        assertTrue(VcsApiUrlValidator.isAllowedForVcs(vcs, "https://api.github.com/repos/org/repo/pulls/1/files"));
        assertTrue(VcsApiUrlValidator.isAllowedForVcs(vcs, "https://API.GITHUB.COM:443/repos/org/repo/pulls/1"));
        assertTrue(VcsApiUrlValidator.isAllowedForVcs(vcs, "https://api.github.com"));
    }

    @Test
    void allowsConfiguredSubpathAndBitbucketCommitRanges() {
        Vcs ghe = vcsWithApiUrl("https://ghe.internal/api/v3/");
        assertTrue(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api/v3/repos/org/repo/pulls/42"));
        assertTrue(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api/v3"));

        Vcs bitbucket = vcsWithApiUrl("https://api.bitbucket.org/2.0");
        assertTrue(VcsApiUrlValidator.isAllowedForVcs(bitbucket,
                "https://api.bitbucket.org/2.0/repositories/org/repo/diff/abc123..def456"));
    }

    @Test
    void allowsLocalhostWireMockWithMatchingPort() {
        Vcs wireMock = vcsWithApiUrl("http://localhost:18080");

        assertTrue(VcsApiUrlValidator.isAllowedForVcs(wireMock, "http://localhost:18080/repos/org/repo/pulls/1"));
        assertTrue(VcsApiUrlValidator.isAllowedForVcs(wireMock, "http://localhost:18080/2.0/repositories/org/repo/diff/a..b"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(wireMock, "http://localhost:18081/repos/org/repo/pulls/1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "https://attacker.example/repos/org/repo/pulls/1",
            "https://api.github.com.evil.com/repos/org/repo/pulls/1",
            "https://evil-api.github.com/repos/org/repo/pulls/1",
            "https://api.github.com@attacker.example/repos/org/repo/pulls/1",
            "https://user:pass@api.github.com/repos/org/repo/pulls/1",
            "http://api.github.com/repos/org/repo/pulls/1",
            "https://api.github.com:8443/repos/org/repo/pulls/1",
            "file:///etc/passwd",
            "/repos/org/repo/pulls/1",
            "https://api.github.com/repos/../secret",
            "https://api.github.com/repos/%2e%2e/secret",
            "https://api.github.com/repos/\\evil",
            "https://api.github.com/repos/%5cevil",
            "https://api.github.com/repos/org/repo#fragment",
            "https://api.github.com/repos/org/repo\r\nX-Injected: true"
    })
    void blocksUntrustedOrMalformedUrls(String candidate) {
        Vcs vcs = vcsWithApiUrl("https://api.github.com");

        assertFalse(VcsApiUrlValidator.isAllowedForVcs(vcs, candidate),
                "Expected URL to be blocked: " + candidate);
    }

    @Test
    void blocksSiblingBasePathAndOutsideBasePath() {
        Vcs ghe = vcsWithApiUrl("https://ghe.internal/api/v3");

        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api/v3evil/repos/org/repo"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/other/repos/org/repo"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api/v3/../other"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api/v3/.%2e/.%2e/admin"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api/v3/%2e./x"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api/v3/%2e/x"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api%2Fv3/repos/org/repo"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(ghe, "https://ghe.internal/api%2fv3/repos/org/repo"));
    }

    @Test
    void failsClosedWhenVcsOrApiUrlIsMissing() {
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(null, "https://api.github.com/repos/org/repo"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(new Vcs(), "https://api.github.com/repos/org/repo"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(vcsWithApiUrl("   "), "https://api.github.com/repos/org/repo"));
        assertFalse(VcsApiUrlValidator.isAllowedForVcs(vcsWithApiUrl("https://user@api.github.com"),
                "https://api.github.com/repos/org/repo"));
    }

    @Test
    void isSameOriginChecksSchemeHostPortAndUserInfo() {
        URI expected = URI.create("https://api.github.com");

        assertTrue(VcsApiUrlValidator.isSameOrigin(expected, URI.create("https://api.github.com:443/path")));
        assertFalse(VcsApiUrlValidator.isSameOrigin(expected, URI.create("http://api.github.com/path")));
        assertFalse(VcsApiUrlValidator.isSameOrigin(expected, URI.create("https://api.github.com:8443/path")));
        assertFalse(VcsApiUrlValidator.isSameOrigin(expected, URI.create("https://api.github.com@evil.com/path")));
        assertFalse(VcsApiUrlValidator.isSameOrigin(null, URI.create("https://api.github.com")));
    }
}
