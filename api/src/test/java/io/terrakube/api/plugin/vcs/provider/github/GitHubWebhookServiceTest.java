package io.terrakube.api.plugin.vcs.provider.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.terrakube.api.rs.job.JobStatus;

public class GitHubWebhookServiceTest {

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnSuccess() {
        String description = GitHubWebhookService.buildCommitStatusDescription(JobStatus.completed,
                "Plan: 2 to add, 0 to change, 1 to destroy.");

        assertEquals("Your task has been completed successfully. Plan: 2 to add, 0 to change, 1 to destroy.",
                description);
    }

    @Test
    public void buildCommitStatusDescriptionOmitsSummaryWhenNull() {
        String description = GitHubWebhookService.buildCommitStatusDescription(JobStatus.completed, null);

        assertEquals("Your task has been completed successfully.", description);
    }

    @Test
    public void buildCommitStatusDescriptionOmitsSummaryWhenBlank() {
        String description = GitHubWebhookService.buildCommitStatusDescription(JobStatus.completed, "   ");

        assertEquals("Your task has been completed successfully.", description);
    }

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnFailure() {
        String description = GitHubWebhookService.buildCommitStatusDescription(JobStatus.failed,
                "Plan: 1 to add, 0 to change, 0 to destroy.");

        assertEquals("Your task has failed. Plan: 1 to add, 0 to change, 0 to destroy.", description);
    }

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnError() {
        String description = GitHubWebhookService.buildCommitStatusDescription(JobStatus.unknown,
                "Plan: 1 to add, 0 to change, 0 to destroy.");

        assertEquals("Your task ran into errors. Plan: 1 to add, 0 to change, 0 to destroy.", description);
    }

    @Test
    public void buildCommitStatusDescriptionDefaultsToQueueMessage() {
        String description = GitHubWebhookService.buildCommitStatusDescription(JobStatus.queue, null);

        assertEquals("Your task is in Terrakube queue.", description);
    }

    @Test
    public void buildCommitStatusDescriptionTruncatesAt140Characters() {
        String longSummary = "Plan: " + "1".repeat(200) + " to add, 0 to change, 0 to destroy.";

        String description = GitHubWebhookService.buildCommitStatusDescription(JobStatus.completed, longSummary);

        assertEquals(140, description.length());
        assertTrue(description.startsWith("Your task has been completed successfully. Plan:"));
    }
}
