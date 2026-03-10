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
    void checkBranch_regexMatch_returnsTrue() {
        assertThat(WebhookEventMatcher.checkBranch("main-branch", eventWithBranch("main.*"))).isTrue();
    }

    @Test
    void checkBranch_noMatch_returnsFalse() {
        assertThat(WebhookEventMatcher.checkBranch("develop", eventWithBranch("main"))).isFalse();
    }

    @Test
    void checkBranch_commaSeparatedList_matchesAny() {
        WebhookEvent event = eventWithBranch("main, develop, release/.*");
        assertThat(WebhookEventMatcher.checkBranch("develop", event)).isTrue();
        assertThat(WebhookEventMatcher.checkBranch("release/1.0", event)).isTrue();
        assertThat(WebhookEventMatcher.checkBranch("feature/foo", event)).isFalse();
    }

    // --- checkFileChanges tests ---

    @Test
    void checkFileChanges_fileMatchesPattern_returnsTrue() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("src/main/App.java"), eventWithPath("src/main/.*"))).isTrue();
    }

    @Test
    void checkFileChanges_noFileMatches_returnsFalse() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("docs/README.md"), eventWithPath("src/.*"))).isFalse();
    }

    @Test
    void checkFileChanges_multiplePatterns_oneMatches_returnsTrue() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("terraform/main.tf"), eventWithPath("src/.*,terraform/.*"))).isTrue();
    }
}
