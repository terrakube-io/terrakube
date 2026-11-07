package io.terrakube.api.plugin.vcs.provider.azdevops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService.ChangesPage;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.webhook.WebhookEventType;

public class AzDevOpsWebhookServiceTest {

    private final AzDevOpsWebhookService subject = new AzDevOpsWebhookService(new ObjectMapper(), null);

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnSuccess() {
        String description = AzDevOpsWebhookService.buildCommitStatusDescription(JobStatus.completed,
                "Plan: 2 to add, 0 to change, 1 to destroy.");

        assertEquals("Your task has been completed successfully. Plan: 2 to add, 0 to change, 1 to destroy.",
                description);
    }

    @Test
    public void buildCommitStatusDescriptionOmitsSummaryWhenNull() {
        String description = AzDevOpsWebhookService.buildCommitStatusDescription(JobStatus.completed, null);

        assertEquals("Your task has been completed successfully.", description);
    }

    @Test
    public void buildCommitStatusDescriptionOmitsSummaryWhenBlank() {
        String description = AzDevOpsWebhookService.buildCommitStatusDescription(JobStatus.completed, "   ");

        assertEquals("Your task has been completed successfully.", description);
    }

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnFailure() {
        String description = AzDevOpsWebhookService.buildCommitStatusDescription(JobStatus.failed,
                "Plan: 1 to add, 0 to change, 0 to destroy.");

        assertEquals("Your task has failed. Plan: 1 to add, 0 to change, 0 to destroy.", description);
    }

    @Test
    public void buildCommitStatusDescriptionAppendsRunSummaryOnError() {
        String description = AzDevOpsWebhookService.buildCommitStatusDescription(JobStatus.unknown,
                "Plan: 1 to add, 0 to change, 0 to destroy.");

        assertEquals("Your task ran into errors. Plan: 1 to add, 0 to change, 0 to destroy.", description);
    }

    @Test
    public void buildCommitStatusDescriptionDefaultsToQueueMessage() {
        String description = AzDevOpsWebhookService.buildCommitStatusDescription(JobStatus.queue, null);

        assertEquals("Your task is in Terrakube queue.", description);
    }

    @Nested
    class TagEvents {

        private static final String PUSH = """
                {"eventType": "git.push", "resource": {"refUpdates": [{"name": "%s", "newObjectId": "abc123"}],
                  "pushedBy": {"uniqueName": "jane@example.com"}}}
                """;

        @Test
        void tagPushSetsTheTag() {
            WebhookResult result = subject.parseAzDevOpsPayload(PUSH.formatted("refs/tags/v2.1.0"), Map.of());

            assertTrue(result.isValid());
            assertEquals("v2.1.0", result.getTag());
            assertEquals("release", result.getEvent());
        }

        @Test
        void branchPushHasNoTag() {
            WebhookResult result = subject.parseAzDevOpsPayload(PUSH.formatted("refs/heads/main"), Map.of());

            assertEquals("push", result.getEvent());
            assertNull(result.getTag());
        }

        @Test
        void tagEventsSubscribeToPushes() {
            assertThat(AzDevOpsWebhookService.resolveAzureEventTypesFromSet(
                    EnumSet.of(WebhookEventType.PULL_REQUEST), true))
                    .containsExactlyInAnyOrder("git.push", "git.pullrequest.created", "git.pullrequest.updated");
            assertThat(AzDevOpsWebhookService.resolveAzureEventTypesFromSet(
                    EnumSet.of(WebhookEventType.PULL_REQUEST), false))
                    .doesNotContain("git.push");
        }
    }

    @Nested
    class ParseChangesPage {

        @Test
        void collectsFilePathsAndStripsTheLeadingSlash() throws Exception {
            String body = "{\"changeEntries\":["
                    + "{\"item\":{\"path\":\"/modules/network/main.tf\",\"gitObjectType\":\"blob\"},\"changeType\":\"edit\"},"
                    + "{\"item\":{\"path\":\"/modules/database/main.tf\",\"gitObjectType\":\"blob\"},\"changeType\":\"add\"}"
                    + "]}";

            ChangesPage page = subject.parseChangesPage(body);

            assertThat(page.files()).containsExactly("modules/network/main.tf", "modules/database/main.tf");
            assertThat(page.nextTop()).isZero();
            assertThat(page.nextSkip()).isZero();
        }

        @Test
        void skipsFolderEntries() throws Exception {
            String body = "{\"changeEntries\":["
                    + "{\"item\":{\"path\":\"/modules\",\"isFolder\":true,\"gitObjectType\":\"tree\"},\"changeType\":\"edit\"},"
                    + "{\"item\":{\"path\":\"/modules/network/main.tf\",\"gitObjectType\":\"blob\"},\"changeType\":\"edit\"}"
                    + "]}";

            ChangesPage page = subject.parseChangesPage(body);

            assertThat(page.files()).containsExactly("modules/network/main.tf");
        }

        @Test
        void reportsNextTopAndNextSkipWhenAnotherPageIsAvailable() throws Exception {
            String body = "{\"changeEntries\":[],\"nextTop\":100,\"nextSkip\":100}";

            ChangesPage page = subject.parseChangesPage(body);

            assertThat(page.nextTop()).isEqualTo(100);
            assertThat(page.nextSkip()).isEqualTo(100);
        }

        @Test
        void nextTopAndNextSkipAreBothZeroWhenAbsent() throws Exception {
            String body = "{\"changeEntries\":[]}";

            ChangesPage page = subject.parseChangesPage(body);

            assertThat(page.nextTop()).isZero();
            assertThat(page.nextSkip()).isZero();
        }
    }
}
