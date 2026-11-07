package io.terrakube.api.plugin.vcs.provider.azdevops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.vcs.WebhookProperties;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService.ChangesPage;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;

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

    @Test
    public void parseAzDevOpsPayloadPullRequestParsesSourceAndTargetBranch() {
        String payload = """
                {
                  "eventType": "git.pullrequest.created",
                  "resource": {
                    "pullRequestId": 10,
                    "sourceRefName": "refs/heads/feature/azure",
                    "targetRefName": "refs/heads/main",
                    "lastMergeSourceCommit": {"commitId": "commit123"},
                    "createdBy": {"displayName": "Azure User"}
                  }
                }
                """;
        WebhookResult result = subject.parseAzDevOpsPayload(payload, Map.of());
        assertEquals("pull_request", result.getEvent());
        assertEquals("feature/azure", result.getBranch());
        assertEquals("main", result.getBaseBranch());
        assertEquals(10, result.getPrNumber());
    }

    @Test
    public void parseAzDevOpsPayloadPullRequestCommentParsesSourceAndTargetBranch() {
        String payload = """
                {
                  "eventType": "ms.vss-code.git-pullrequest-comment-event",
                  "resource": {
                    "comment": {
                      "content": "terrakube apply",
                      "author": {"displayName": "Azure User"}
                    },
                    "pullRequest": {
                      "pullRequestId": 10,
                      "sourceRefName": "refs/heads/feature/azure",
                      "targetRefName": "refs/heads/main",
                      "lastMergeSourceCommit": {"commitId": "commit123"}
                    }
                  }
                }
                """;
        WebhookResult result = subject.parseAzDevOpsPayload(payload, Map.of());
        assertTrue(result.isPrComment());
        assertEquals("apply", result.getCommentCommand());
        assertEquals("feature/azure", result.getBranch());
        assertEquals("main", result.getBaseBranch());
    }

    @Test
    public void parseAzDevOpsPayloadPullRequestHandlesMissingTargetRefNameGracefully() {
        String payload = """
                {
                  "eventType": "git.pullrequest.created",
                  "resource": {
                    "pullRequestId": 10,
                    "sourceRefName": "refs/heads/feature/azure",
                    "lastMergeSourceCommit": {"commitId": "commit123"}
                  }
                }
                """;
        WebhookResult result = subject.parseAzDevOpsPayload(payload, Map.of());
        assertEquals("pull_request", result.getEvent());
        assertEquals("feature/azure", result.getBranch());
        assertNull(result.getBaseBranch());
    }

    @Test
    public void parseAzDevOpsPayloadPullRequestCommentHandlesMissingTargetRefNameGracefully() {
        String payload = """
                {
                  "eventType": "ms.vss-code.git-pullrequest-comment-event",
                  "resource": {
                    "comment": {
                      "content": "terrakube plan",
                      "author": {"displayName": "Azure User"}
                    },
                    "pullRequest": {
                      "pullRequestId": 10,
                      "sourceRefName": "refs/heads/feature/azure",
                      "lastMergeSourceCommit": {"commitId": "commit123"}
                    }
                  }
                }
                """;
        WebhookResult result = subject.parseAzDevOpsPayload(payload, Map.of());
        assertTrue(result.isPrComment());
        assertEquals("feature/azure", result.getBranch());
        assertNull(result.getBaseBranch());
    }

    @Test
    public void stripRefPrefixNormalizesBranchAndTagRefs() {
        assertEquals("main", subject.stripRefPrefix("refs/heads/main"));
        assertEquals("v1.0", subject.stripRefPrefix("refs/tags/v1.0"));
        assertEquals("feature/azure", subject.stripRefPrefix("feature/azure"));
        assertNull(subject.stripRefPrefix(null));
    }

    @Test
    public void processWebhookDoesNotCallFetchPrTargetBranchWhenFlagIsFalse() {
        WebhookProperties properties = new WebhookProperties();
        properties.setPrBaseBranchFilter(false);
        AzDevOpsWebhookService service = Mockito.spy(new AzDevOpsWebhookService(new ObjectMapper(), null, properties));
        Mockito.doReturn(new ArrayList<>()).when(service).getPullRequestChanges(any(), any(), any(), anyInt());
        Mockito.doReturn("main").when(service).fetchPrTargetBranch(any(), any(), any(), anyInt());

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setSource("https://dev.azure.com/myorg/myproject/_git/myrepo");
        Vcs vcs = new Vcs();
        workspace.setVcs(vcs);

        String payload = """
                {
                  "eventType": "git.pullrequest.created",
                  "resource": {
                    "pullRequestId": 10,
                    "sourceRefName": "refs/heads/feature/azure",
                    "lastMergeSourceCommit": {"commitId": "commit123"},
                    "repository": {"id": "repo-id-123"}
                  }
                }
                """;

        String token = "dummyToken";
        WebhookResult result = service.processWebhook(payload, Map.of("x-terrakube-token", token), token, workspace);

        assertEquals("pull_request", result.getEvent());
        assertEquals("feature/azure", result.getBranch());
        assertNull(result.getBaseBranch());
        Mockito.verify(service, Mockito.never()).fetchPrTargetBranch(any(), any(), any(), anyInt());
    }

    @Test
    public void processWebhookCallsFetchPrTargetBranchWhenFlagIsTrueAndTargetMissing() {
        WebhookProperties properties = new WebhookProperties();
        properties.setPrBaseBranchFilter(true);
        AzDevOpsWebhookService service = Mockito.spy(new AzDevOpsWebhookService(new ObjectMapper(), null, properties));
        Mockito.doReturn(new ArrayList<>()).when(service).getPullRequestChanges(any(), any(), any(), anyInt());
        Mockito.doReturn("main").when(service).fetchPrTargetBranch(any(), any(), any(), anyInt());

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setSource("https://dev.azure.com/myorg/myproject/_git/myrepo");
        Vcs vcs = new Vcs();
        workspace.setVcs(vcs);

        String payload = """
                {
                  "eventType": "git.pullrequest.created",
                  "resource": {
                    "pullRequestId": 10,
                    "sourceRefName": "refs/heads/feature/azure",
                    "lastMergeSourceCommit": {"commitId": "commit123"},
                    "repository": {"id": "repo-id-123"}
                  }
                }
                """;

        String token = "dummyToken";
        WebhookResult result = service.processWebhook(payload, Map.of("x-terrakube-token", token), token, workspace);

        assertEquals("pull_request", result.getEvent());
        assertEquals("feature/azure", result.getBranch());
        assertEquals("main", result.getBaseBranch());
        Mockito.verify(service, Mockito.times(1)).fetchPrTargetBranch(any(), any(), any(), anyInt());
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
