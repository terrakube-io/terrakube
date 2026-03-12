package io.terrakube.api.plugin.vcs;

import java.util.List;

import io.terrakube.api.rs.webhook.WebhookEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventMatcherTest {

    private WebhookEvent eventWithBranch(String branch) {
        WebhookEvent event = new WebhookEvent();
        event.setBranch(branch);
        return event;
    }

    private WebhookEvent eventWithPath(String path) {
        WebhookEvent event = new WebhookEvent();
        event.setPath(path);
        return event;
    }

    // --- checkBranch tests ---

    @Test
    void checkBranch_exactMatch_returnsTrue() {
        assertThat(WebhookEventMatcher.checkBranch("main", eventWithBranch("main"))).isTrue();
    }

    @Test
    void checkBranch_globMatch_returnsTrue() {
        assertThat(WebhookEventMatcher.checkBranch("main-branch", eventWithBranch("main*"))).isTrue();
    }

    @Test
    void checkBranch_noMatch_returnsFalse() {
        assertThat(WebhookEventMatcher.checkBranch("develop", eventWithBranch("main"))).isFalse();
    }

    @Test
    void checkBranch_commaSeparatedList_matchesAny() {
        WebhookEvent event = eventWithBranch("main, develop, release/*");
        assertThat(WebhookEventMatcher.checkBranch("develop", event)).isTrue();
        assertThat(WebhookEventMatcher.checkBranch("release/1.0", event)).isTrue();
        assertThat(WebhookEventMatcher.checkBranch("feature/foo", event)).isFalse();
    }

    // --- checkFileChanges tests ---

    @Test
    void checkFileChanges_fileMatchesPattern_returnsTrue() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("src/main/App.java"), eventWithPath("src/main/*"))).isTrue();
    }

    @Test
    void checkFileChanges_noFileMatches_returnsFalse() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("docs/README.md"), eventWithPath("src/*"))).isFalse();
    }

    @Test
    void checkFileChanges_multiplePatterns_oneMatches_returnsTrue() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("terraform/main.tf"), eventWithPath("src/*,terraform/*"))).isTrue();
    }

    // --- glob matching tests ---

    @Test
    void globMatch_questionMark_matchesSingleChar() {
        assertThat(WebhookEventMatcher.globMatch("main", "mai?")).isTrue();
        assertThat(WebhookEventMatcher.globMatch("main", "ma??")).isTrue();
        assertThat(WebhookEventMatcher.globMatch("main", "m?")).isFalse();
    }

    @Test
    void globMatch_escapesRegexSpecialChars() {
        assertThat(WebhookEventMatcher.globMatch("file.txt", "file.txt")).isTrue();
        assertThat(WebhookEventMatcher.globMatch("fileatxt", "file.txt")).isFalse();
    }

    @Test
    void globMatch_invalidPattern_returnsFalse() {
        assertThat(WebhookEventMatcher.globMatch("test", null)).isFalse();
    }

    // --- globToSafeRegex prevents ReDoS ---

    @Test
    void globToSafeRegex_escapesNestedQuantifiers() {
        String regex = WebhookEventMatcher.globToSafeRegex("(a+)+$");
        // Nested quantifiers should be escaped, not treated as regex
        assertThat(regex).isEqualTo("\\(a\\+\\)\\+\\$");
    }
}
