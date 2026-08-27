package io.terrakube.api;

import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.mockito.Mockito.when;

public class VcsAzureDevopsTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    private Vcs createAzureVcs() {
        Vcs vcs = new Vcs();
        vcs.setAccessToken("1234567890");
        vcs.setClientId("123");
        vcs.setClientSecret("123");
        vcs.setName("azure");
        vcs.setDescription("1234");
        vcs.setVcsType(VcsType.AZURE_DEVOPS);
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcsRepository.save(vcs);
    }

    private Workspace createWorkspace(Vcs vcs) {
        Workspace workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        // points the org scoped base url at the WireMock server: <host>/myorg/myproject/myrepo
        workspace.setSource("http://localhost:" + wireMockServer.port() + "/myorg/myproject/myrepo");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0");
        workspace.setVcs(vcs);
        workspace.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return workspaceRepository.save(workspace);
    }

    private void cleanup(Workspace workspace, Vcs vcs) {
        workspace.setDeleted(true);
        workspace.setVcs(null);
        workspaceRepository.save(workspace);
        vcsRepository.delete(vcs);
    }

    @Test
    void azureDevopsWebhookPush() {
        String payload = """
                {
                  "eventType": "git.push",
                  "publisherId": "tfs",
                  "resource": {
                    "commits": [ { "commitId": "33b55f7cb7e7e245323987634f960cf4a6e6bc74" } ],
                    "refUpdates": [ {
                        "name": "refs/heads/main",
                        "oldObjectId": "aaaa",
                        "newObjectId": "33b55f7cb7e7e245323987634f960cf4a6e6bc74"
                    } ],
                    "repository": {
                      "id": "repo-guid",
                      "name": "myrepo",
                      "project": { "id": "proj-guid", "name": "myproject" }
                    },
                    "pushedBy": { "displayName": "John Doe", "uniqueName": "john@example.com" }
                  }
                }""";

        String changesResponse = "{ \"changes\": [ " +
                "{ \"item\": { \"gitObjectType\": \"blob\", \"path\": \"/work1/main.tf\" }, \"changeType\": \"edit\" }, " +
                "{ \"item\": { \"gitObjectType\": \"tree\", \"path\": \"/work1\", \"isFolder\": true }, \"changeType\": \"edit\" } " +
                "] }";

        stubFor(get(urlPathEqualTo("/myorg/myproject/_apis/git/repositories/repo-guid/commits/33b55f7cb7e7e245323987634f960cf4a6e6bc74/changes"))
                .withPort(wireMockServer.port())
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", "application/json")
                        .withBody(changesResponse)));

        Vcs vcs = createAzureVcs();
        Workspace workspace = createWorkspace(vcs);

        WebhookResult result = new WebhookResult();
        try {
            result = azDevOpsWebhookService.handleEvent(payload, result, workspace);
        } catch (Exception e) {
            Assert.isTrue(false, "Unexpected exception: " + e.getMessage());
        }

        Assert.isTrue("push".equals(result.getEvent()), "Event is not push");
        Assert.isTrue("main".equals(result.getBranch()), "Branch is not main");
        Assert.isTrue("33b55f7cb7e7e245323987634f960cf4a6e6bc74".equals(result.getCommit()), "Commit id mismatch");
        Assert.isTrue("john@example.com".equals(result.getCreatedBy()), "Created by mismatch");
        Assert.isTrue(result.getFileChanges().size() == 1, "File changes (excluding folders) is not 1");
        Assert.isTrue(result.getFileChanges().contains("work1/main.tf"), "File change path mismatch");

        cleanup(workspace, vcs);
    }

    @Test
    void azureDevopsWebhookTag() {
        String payload = """
                {
                  "eventType": "git.push",
                  "resource": {
                    "commits": [ { "commitId": "abc" } ],
                    "refUpdates": [ {
                        "name": "refs/tags/v1.0",
                        "newObjectId": "abc"
                    } ],
                    "repository": { "id": "repo-guid", "project": { "id": "proj-guid" } },
                    "pushedBy": { "uniqueName": "john@example.com" }
                  }
                }""";

        Vcs vcs = createAzureVcs();
        Workspace workspace = createWorkspace(vcs);

        WebhookResult result = new WebhookResult();
        try {
            result = azDevOpsWebhookService.handleEvent(payload, result, workspace);
        } catch (Exception e) {
            Assert.isTrue(false, "Unexpected exception: " + e.getMessage());
        }

        Assert.isTrue(result.isRelease(), "Tag push is not flagged as release");
        Assert.isTrue("release".equals(result.getEvent()), "Event is not release");
        Assert.isTrue("v1.0".equals(result.getBranch()), "Tag name mismatch");

        cleanup(workspace, vcs);
    }

    @Test
    void azureDevopsWebhookPullRequest() {
        String payload = """
                {
                  "eventType": "git.pullrequest.created",
                  "resource": {
                    "pullRequestId": 5,
                    "status": "active",
                    "sourceRefName": "refs/heads/feature",
                    "targetRefName": "refs/heads/main",
                    "lastMergeSourceCommit": { "commitId": "def456" },
                    "createdBy": { "displayName": "Jane", "uniqueName": "jane@example.com" },
                    "repository": { "id": "repo-guid", "project": { "id": "proj-guid" } }
                  }
                }""";

        stubFor(get(urlPathEqualTo("/myorg/myproject/_apis/git/repositories/repo-guid/pullRequests/5/iterations"))
                .withPort(wireMockServer.port())
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"value\": [ { \"id\": 1 }, { \"id\": 2 } ] }")));

        stubFor(get(urlPathEqualTo("/myorg/myproject/_apis/git/repositories/repo-guid/pullRequests/5/iterations/2/changes"))
                .withPort(wireMockServer.port())
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"changeEntries\": [ { \"item\": { \"gitObjectType\": \"blob\", \"path\": \"/work1/main.tf\" } } ] }")));

        Vcs vcs = createAzureVcs();
        Workspace workspace = createWorkspace(vcs);

        WebhookResult result = new WebhookResult();
        try {
            result = azDevOpsWebhookService.handleEvent(payload, result, workspace);
        } catch (Exception e) {
            Assert.isTrue(false, "Unexpected exception: " + e.getMessage());
        }

        Assert.isTrue("pull_request".equals(result.getEvent()), "Event is not pull_request");
        Assert.isTrue("feature".equals(result.getBranch()), "Branch is not feature");
        Assert.isTrue("def456".equals(result.getCommit()), "Commit id mismatch");
        Assert.isTrue(result.getPrNumber().intValue() == 5, "PR number mismatch");
        Assert.isTrue(result.getFileChanges().contains("work1/main.tf"), "PR file change path mismatch");

        cleanup(workspace, vcs);
    }

    @Test
    void azureDevopsWebhookTokenValidation() {
        String payload = """
                {
                  "eventType": "git.push",
                  "resource": {
                    "refUpdates": [ { "name": "refs/tags/v1.0", "newObjectId": "abc" } ],
                    "repository": { "id": "repo-guid", "project": { "id": "proj-guid" } },
                    "pushedBy": { "uniqueName": "john@example.com" }
                  }
                }""";

        Vcs vcs = createAzureVcs();
        Workspace workspace = createWorkspace(vcs);

        String secret = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));

        // Missing token header -> rejected
        WebhookResult invalid = azDevOpsWebhookService.processWebhook(payload, new HashMap<>(), secret, workspace);
        Assert.isTrue(!invalid.isValid(), "Webhook without token header should be invalid");

        // Wrong token header -> rejected
        Map<String, String> wrongHeaders = new HashMap<>();
        wrongHeaders.put("x-terrakube-token", "not-the-secret");
        WebhookResult wrong = azDevOpsWebhookService.processWebhook(payload, wrongHeaders, secret, workspace);
        Assert.isTrue(!wrong.isValid(), "Webhook with wrong token header should be invalid");

        // Correct token header -> accepted (tag push, no API calls needed)
        Map<String, String> headers = new HashMap<>();
        headers.put("x-terrakube-token", secret);
        WebhookResult valid = azDevOpsWebhookService.processWebhook(payload, headers, secret, workspace);
        Assert.isTrue(valid.isValid(), "Webhook with correct token header should be valid");
        Assert.isTrue(valid.isRelease(), "Tag push should be flagged as release");

        cleanup(workspace, vcs);
    }
}
