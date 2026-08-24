package io.terrakube.api.plugin.vcs.provider.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.rs.job.JobStatus;

public class GitHubWebhookServiceTest {

    private final GitHubWebhookService subject = new GitHubWebhookService(new ObjectMapper(), null);

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

    @Nested
    class ParseChangedFilesFromPage {

        @Test
        void collectsFilenamesFromEveryEntry() throws Exception {
            String body = "["
                    + "{\"filename\":\"modules/network/main.tf\"},"
                    + "{\"filename\":\"modules/database/main.tf\"}"
                    + "]";

            List<String> files = subject.parseChangedFilesFromPage(body);

            assertThat(files).containsExactly("modules/network/main.tf", "modules/database/main.tf");
        }

        @Test
        void includesPreviousFilenameForRenamedEntries() throws Exception {
            String body = "[{\"filename\":\"modules/network-v2/main.tf\","
                    + "\"status\":\"renamed\",\"previous_filename\":\"modules/network/main.tf\"}]";

            List<String> files = subject.parseChangedFilesFromPage(body);

            assertThat(files).containsExactlyInAnyOrder("modules/network-v2/main.tf", "modules/network/main.tf");
        }

        @Test
        void doesNotAddAPreviousFilenameEntryWhenAbsent() throws Exception {
            String body = "[{\"filename\":\"modules/network/main.tf\"}]";

            List<String> files = subject.parseChangedFilesFromPage(body);

            assertThat(files).containsExactly("modules/network/main.tf");
        }
    }

    @Nested
    class WithPerPage {

        @Test
        void appendsAsFirstQueryParamWhenUrlHasNone() {
            String url = subject.withPerPage("https://api.github.com/repos/owner/repo/pulls/42/files");

            assertThat(url).isEqualTo("https://api.github.com/repos/owner/repo/pulls/42/files?per_page=100");
        }

        @Test
        void appendsAsAdditionalQueryParamWhenUrlAlreadyHasOne() {
            String url = subject.withPerPage("https://api.github.com/repos/owner/repo/pulls/42/files?page=2");

            assertThat(url).isEqualTo("https://api.github.com/repos/owner/repo/pulls/42/files?page=2&per_page=100");
        }
    }

    @Nested
    class ExtractNextPageUrl {

        @Test
        void returnsTheRelNextUrlWhenPresentAmongMultipleLinks() {
            String linkHeader = "<https://api.github.com/repos/owner/repo/pulls/42/files?page=2>; rel=\"next\", "
                    + "<https://api.github.com/repos/owner/repo/pulls/42/files?page=5>; rel=\"last\"";

            String next = subject.extractNextPageUrl(linkHeader);

            assertThat(next).isEqualTo("https://api.github.com/repos/owner/repo/pulls/42/files?page=2");
        }

        @Test
        void returnsNullWhenOnlyRelLastIsPresent() {
            String linkHeader = "<https://api.github.com/repos/owner/repo/pulls/42/files?page=1>; rel=\"first\", "
                    + "<https://api.github.com/repos/owner/repo/pulls/42/files?page=1>; rel=\"prev\"";

            assertNull(subject.extractNextPageUrl(linkHeader));
        }

        @Test
        void returnsNullForMissingHeader() {
            assertNull(subject.extractNextPageUrl(null));
        }

        @Test
        void returnsNullForBlankHeader() {
            assertNull(subject.extractNextPageUrl("   "));
        }
    }
}
