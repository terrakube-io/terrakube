package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.execution.WorkspaceBranchPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class WebhookResultTest {

    @Test
    public void normalizedEventPush() {
        WebhookResult result = new WebhookResult();
        result.setEvent("push");
        assertEquals("push", result.getNormalizedEvent());
    }

    @Test
    public void normalizedEventPullRequest() {
        WebhookResult result = new WebhookResult();
        result.setEvent("pull_request");
        assertEquals("pull_request", result.getNormalizedEvent());
    }

    @Test
    public void normalizedEventMergeRequest() {
        WebhookResult result = new WebhookResult();
        result.setEvent("merge_request");
        assertEquals("pull_request", result.getNormalizedEvent());
    }

    @Test
    public void normalizedEventIssueComment() {
        WebhookResult result = new WebhookResult();
        result.setEvent("issue_comment");
        assertEquals("pr_comment", result.getNormalizedEvent());
    }

    @Test
    public void normalizedEventNote() {
        WebhookResult result = new WebhookResult();
        result.setEvent("note");
        assertEquals("pr_comment", result.getNormalizedEvent());
    }

    @Test
    public void normalizedEventRelease() {
        WebhookResult result = new WebhookResult();
        result.setEvent("release");
        assertEquals("release", result.getNormalizedEvent());
    }

    @Test
    public void normalizedEventUnknown() {
        WebhookResult result = new WebhookResult();
        result.setEvent("something_else");
        assertEquals("unknown", result.getNormalizedEvent());
    }

    @Test
    public void normalizedEventNullDoesNotThrow() {
        WebhookResult result = new WebhookResult();
        assertNull(result.getEvent());
        assertEquals("unknown", result.getNormalizedEvent());
    }

    @Test
    public void prCommentFieldsAreSetCorrectly() {
        WebhookResult result = new WebhookResult();
        result.setPrComment(true);
        result.setCommentBody("terrakube apply");
        result.setCommentCommand("apply");

        assertTrue(result.isPrComment());
        assertEquals("terrakube apply", result.getCommentBody());
        assertEquals("apply", result.getCommentCommand());
    }

    @Test
    public void defaultPrCommentFieldsAreFalseAndNull() {
        WebhookResult result = new WebhookResult();

        assertFalse(result.isPrComment());
        assertNull(result.getCommentBody());
        assertNull(result.getCommentCommand());
    }

    @Test
    public void aPushIsAPushForTheBranchPolicy() {
        WebhookResult result = new WebhookResult();
        result.setEvent("push");
        assertEquals(WorkspaceBranchPolicy.Source.PUSH, result.getBranchPolicySource());
        assertEquals(WorkspaceBranchPolicy.Source.PUSH, new WebhookResult().getBranchPolicySource());
    }

    @Test
    public void aPullRequestEventAndACommentCommandAreBothAPullRequest() {
        WebhookResult pullRequest = new WebhookResult();
        pullRequest.setEvent("pull_request");
        WebhookResult mergeRequest = new WebhookResult();
        mergeRequest.setEvent("merge_request");
        WebhookResult withNumber = new WebhookResult();
        withNumber.setPrNumber(7);
        WebhookResult comment = new WebhookResult();
        comment.setPrComment(true);

        for (WebhookResult result : new WebhookResult[] {pullRequest, mergeRequest, withNumber, comment}) {
            assertEquals(WorkspaceBranchPolicy.Source.PULL_REQUEST, result.getBranchPolicySource());
        }
    }

    @Test
    public void aReleaseIsAReleaseEvenWhenItCarriesAPullRequestNumber() {
        WebhookResult result = new WebhookResult();
        result.setRelease(true);
        result.setPrNumber(7);
        assertEquals(WorkspaceBranchPolicy.Source.RELEASE, result.getBranchPolicySource());
    }

    @Test
    public void baseBranchCanBeStoredAndRetrieved() {
        WebhookResult result = new WebhookResult();
        assertNull(result.getBaseBranch());
        result.setBaseBranch("main");
        assertEquals("main", result.getBaseBranch());
    }

    @Test
    public void isPullRequestOrCommentIdentifiesPrsAndComments() {
        WebhookResult push = new WebhookResult();
        push.setEvent("push");
        assertFalse(push.isPullRequestOrComment());

        WebhookResult pr = new WebhookResult();
        pr.setEvent("pull_request");
        assertTrue(pr.isPullRequestOrComment());

        WebhookResult mr = new WebhookResult();
        mr.setEvent("merge_request");
        assertTrue(mr.isPullRequestOrComment());

        WebhookResult comment = new WebhookResult();
        comment.setPrComment(true);
        assertTrue(comment.isPullRequestOrComment());

        WebhookResult withNumber = new WebhookResult();
        withNumber.setPrNumber(10);
        assertTrue(withNumber.isPullRequestOrComment());

        WebhookResult release = new WebhookResult();
        release.setRelease(true);
        release.setPrNumber(10);
        release.setEvent("release");
        assertFalse(release.isPullRequestOrComment());
    }

    @Test
    void deserializationIgnoresUnknownPropertiesForRollingUpgrades() throws Exception {
        String json = """
                {
                    "branch": "main",
                    "baseBranch": "release/1.0",
                    "someFutureField": "futureValue"
                }
                """;
        WebhookResult result = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, WebhookResult.class);
        assertEquals("main", result.getBranch());
        assertEquals("release/1.0", result.getBaseBranch());
    }

    @Test
    void copyCreatesIndependentCopy() {
        WebhookResult original = new WebhookResult();
        original.setBranch("feature/test");
        original.setBaseBranch("main");
        original.setCommit("abc123");
        original.setPrNumber(42);
        original.setFileChanges(new java.util.ArrayList<>(java.util.List.of("main.tf")));

        WebhookResult copied = original.copy();
        assertEquals(original, copied);
        assertEquals(original.getBranch(), copied.getBranch());
        assertEquals(original.getBaseBranch(), copied.getBaseBranch());
        assertEquals(original.getCommit(), copied.getCommit());
        assertEquals(original.getPrNumber(), copied.getPrNumber());
        assertEquals(original.getFileChanges(), copied.getFileChanges());

        // Modifying the copy doesn't mutate original
        copied.setBaseBranch("develop");
        copied.getFileChanges().add("variables.tf");
        assertEquals("main", original.getBaseBranch());
        assertEquals(1, original.getFileChanges().size());
    }

    @Test
    void theBranchPolicySourceIsNotSerialized() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new WebhookResult());

        assertFalse(json.contains("branchPolicySource"), json);
    }
}
