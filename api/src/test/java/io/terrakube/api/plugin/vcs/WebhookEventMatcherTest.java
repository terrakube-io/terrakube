package io.terrakube.api.plugin.vcs;

import java.util.List;

import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import io.terrakube.api.rs.webhook.WebhookEventType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookEventMatcherTest {

    private WebhookEvent eventWithBranch(String branch) {
        WebhookEvent event = new WebhookEvent();
        event.setBranch(branch);
        return event;
    }

    private WebhookEvent eventWithPath(String path) {
        return eventWithPath(path, WebhookEventPathType.REGEX);
    }

    private WebhookEvent eventWithPath(String path, WebhookEventPathType pathType) {
        WebhookEvent event = new WebhookEvent();
        event.setPath(path);
        event.setPathType(pathType);
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
                List.of("src/main/App.java"), eventWithPath("src/main/*", WebhookEventPathType.PATTERN))).isTrue();
    }

    @Test
    void checkFileChanges_noFileMatches_returnsFalse() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("docs/README.md"), eventWithPath("src/*", WebhookEventPathType.PATTERN))).isFalse();
    }

    @Test
    void checkFileChanges_multiplePatterns_oneMatches_returnsTrue() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("terraform/main.tf"), eventWithPath("src/*,terraform/*", WebhookEventPathType.PATTERN))).isTrue();
    }

    @Test
    void checkFileChanges_regexFileMatchesPattern_returnsTrue() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("src/main/App.java"), eventWithPath("src/main/.*"))).isTrue();
    }

    @Test
    void checkFileChanges_regexNoFileMatches_returnsFalse() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("docs/README.md"), eventWithPath("src/.*"))).isFalse();
    }

    @Test
    void checkFileChanges_regexMultiplePatterns_oneMatches_returnsTrue() {
        assertThat(WebhookEventMatcher.checkFileChanges(
                List.of("terraform/main.tf"), eventWithPath("src/.*,terraform/.*"))).isTrue();
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

    // --- resolveBranchToMatch and target-branch tests ---

    @Test
    void resolveBranchToMatch_pullRequest_usesBaseBranchWhenEnabled() {
        WebhookResult result = new WebhookResult();
        result.setBranch("feature/login");
        result.setBaseBranch("refs/heads/main");

        String resolved = WebhookEventMatcher.resolveBranchToMatch(result, WebhookEventType.PULL_REQUEST, true);
        assertThat(resolved).isEqualTo("main");
    }

    @Test
    void resolveBranchToMatch_pullRequest_fallsBackToHeadBranchWhenDisabledOrBlank() {
        WebhookResult result = new WebhookResult();
        result.setBranch("feature/login");
        result.setBaseBranch("main");

        // Disabled
        assertThat(WebhookEventMatcher.resolveBranchToMatch(result, WebhookEventType.PULL_REQUEST, false))
                .isEqualTo("feature/login");

        // Blank baseBranch
        result.setBaseBranch("   ");
        assertThat(WebhookEventMatcher.resolveBranchToMatch(result, WebhookEventType.PULL_REQUEST, true))
                .isEqualTo("feature/login");

        result.setBaseBranch(null);
        assertThat(WebhookEventMatcher.resolveBranchToMatch(result, WebhookEventType.PULL_REQUEST, true))
                .isEqualTo("feature/login");
    }

    @Test
    void resolveBranchToMatch_pushEvent_alwaysUsesPushBranch() {
        WebhookResult result = new WebhookResult();
        result.setBranch("feature/login");
        result.setBaseBranch("main");

        assertThat(WebhookEventMatcher.resolveBranchToMatch(result, WebhookEventType.PUSH, true))
                .isEqualTo("feature/login");
    }

    @Test
    void findMatchingEvent_pullRequest_matchesTriggerBranchAgainstBaseBranch() {
        Webhook webhook = new Webhook();
        WebhookEventRepository repository = mock(WebhookEventRepository.class);

        WebhookEvent event = new WebhookEvent();
        event.setEvent(WebhookEventType.PULL_REQUEST);
        event.setBranch("main");
        event.setPath(".*");
        event.setPathType(WebhookEventPathType.REGEX);
        event.setTemplateId("template-1");

        when(repository.findByWebhookAndEventOrderByPriorityAsc(webhook, WebhookEventType.PULL_REQUEST))
                .thenReturn(List.of(event));

        WebhookResult result = new WebhookResult();
        result.setEvent("pull_request");
        result.setBranch("feature/new-feature");
        result.setBaseBranch("main");
        result.setFileChanges(List.of("main.tf"));

        WebhookEvent matched = WebhookEventMatcher.findMatchingEvent(result, webhook, repository, true);
        assertThat(matched).isSameAs(event);

        // When baseBranch does not match
        result.setBaseBranch("develop");
        assertThatThrownBy(() -> WebhookEventMatcher.findMatchingEvent(result, webhook, repository, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
