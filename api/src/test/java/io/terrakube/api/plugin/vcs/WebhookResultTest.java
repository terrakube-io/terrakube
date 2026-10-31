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
    void theBranchPolicySourceIsNotSerialized() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new WebhookResult());

        assertFalse(json.contains("branchPolicySource"), json);
    }
}
