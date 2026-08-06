package io.terrakube.api.plugin.vcs.provider.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.rs.job.JobStatus;

public class GitLabWebhookServiceTest {

    private GitLabWebhookService newService() {
        return new GitLabWebhookService(new ObjectMapper(), "localhost", "http://localhost",
                WebClient.builder(), 30, 25);
    }

    @Test
    public void parseGitLabPayloadParsesPushEvent() {
        String payload = "{\"object_kind\":\"push\",\"ref\":\"refs/heads/main\",\"checkout_sha\":\"abc123\","
                + "\"user_username\":\"jdoe\",\"commits\":[{\"added\":[\"main.tf\"],"
                + "\"modified\":[\"variables.tf\"],\"removed\":[]}]}";

        WebhookResult result = newService().parseGitLabPayload(payload, Map.of());

        assertEquals("push", result.getEvent());
        assertEquals("main", result.getBranch());
        assertEquals("abc123", result.getCommit());
        assertTrue(result.isValid());
        assertTrue(result.getFileChanges().contains("main.tf"));
        assertTrue(result.getFileChanges().contains("variables.tf"));
    }

    @Test
    public void parseGitLabPayloadStoresMergeRequestIidForFileFetch() {
        String payload = "{\"object_kind\":\"merge_request\",\"object_attributes\":{\"iid\":42,"
                + "\"action\":\"open\",\"source_branch\":\"feature\",\"last_commit\":{\"id\":\"def456\"}}}";

        WebhookResult result = newService().parseGitLabPayload(payload, Map.of());

        assertEquals("merge_request", result.getEvent());
        assertEquals("feature", result.getBranch());
        assertEquals("def456", result.getCommit());
        assertEquals(42L, result.getPrNumber());
        // The MR iid is the marker used later to resolve the changed files per workspace
        assertEquals("42", result.getPrFilesUrl());
        assertTrue(result.isValid());
    }

    @Test
    public void parseGitLabPayloadParsesReleaseCreate() {
        String payload = "{\"object_kind\":\"release\",\"action\":\"create\",\"tag\":\"v1.0.0\","
                + "\"name\":\"Release 1.0.0\"}";

        WebhookResult result = newService().parseGitLabPayload(payload, Map.of());

        assertEquals("release", result.getEvent());
        assertTrue(result.isRelease());
        assertTrue(result.isValid());
        assertEquals("Release 1.0.0", result.getBranch());
    }

    @Test
    public void parseGitLabPayloadMarksUnknownEventInvalid() {
        String payload = "{\"object_kind\":\"pipeline\"}";

        WebhookResult result = newService().parseGitLabPayload(payload, Map.of());

        assertEquals("pipeline", result.getEvent());
        assertFalse(result.isValid());
    }

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnSuccess() {
        String description = GitLabWebhookService.buildCommitStatusDescription(JobStatus.completed,
                "Plan: 2 to add, 0 to change, 1 to destroy.");

        assertEquals("Your task has been completed successfully. Plan: 2 to add, 0 to change, 1 to destroy.",
                description);
    }

    @Test
    public void buildCommitStatusDescriptionOmitsSummaryWhenNull() {
        String description = GitLabWebhookService.buildCommitStatusDescription(JobStatus.completed, null);

        assertEquals("Your task has been completed successfully.", description);
    }

    @Test
    public void buildCommitStatusDescriptionOmitsSummaryWhenBlank() {
        String description = GitLabWebhookService.buildCommitStatusDescription(JobStatus.completed, "   ");

        assertEquals("Your task has been completed successfully.", description);
    }

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnFailure() {
        String description = GitLabWebhookService.buildCommitStatusDescription(JobStatus.failed,
                "Plan: 1 to add, 0 to change, 0 to destroy.");

        assertEquals("Your task has failed. Plan: 1 to add, 0 to change, 0 to destroy.", description);
    }

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnError() {
        String description = GitLabWebhookService.buildCommitStatusDescription(JobStatus.unknown,
                "Plan: 1 to add, 0 to change, 0 to destroy.");

        assertEquals("Your task ran into errors. Plan: 1 to add, 0 to change, 0 to destroy.", description);
    }

    @Test
    public void buildCommitStatusDescriptionDefaultsToQueueMessage() {
        String description = GitLabWebhookService.buildCommitStatusDescription(JobStatus.queue, null);

        assertEquals("Your task is in Terrakube queue.", description);
    }
}
