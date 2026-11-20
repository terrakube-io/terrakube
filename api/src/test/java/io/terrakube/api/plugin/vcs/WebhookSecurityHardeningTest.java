package io.terrakube.api.plugin.vcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookSecurityHardeningTest {

    private WireMockServer vcsServer;
    private WireMockServer attackerServer;
    private ObjectMapper objectMapper;
    private RestTemplate hardenedRestTemplate;

    @BeforeEach
    void setUp() {
        vcsServer = new WireMockServer(options().dynamicPort());
        attackerServer = new WireMockServer(options().dynamicPort());
        vcsServer.start();
        attackerServer.start();
        attackerServer.stubFor(WireMock.any(urlPathMatching(".*"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));
        objectMapper = new ObjectMapper();
        hardenedRestTemplate = new WebhookHttpClientConfig().webhookRestTemplate(5, 10, 2, 4);
    }

    @AfterEach
    void tearDown() {
        vcsServer.stop();
        attackerServer.stop();
    }

    private String vcsBaseUrl() {
        return "http://localhost:" + vcsServer.port();
    }

    private String attackerBaseUrl() {
        return "http://localhost:" + attackerServer.port();
    }

    private static String signHmacSha256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String signBitbucketHmacSha256(String secret, String payload) throws Exception {
        return signHmacSha256(secret, payload);
    }

    @Test
    void githubV1PullRequestBlocksAttackerPullRequestUrl() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("scoped-token");
        GitHubWebhookService gitHubService = new GitHubWebhookService(objectMapper, tokenService);
        gitHubService.setWebhookRestTemplate(hardenedRestTemplate);

        vcsServer.stubFor(get(urlEqualTo("/repos/victim/repo/pulls/1/files?per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"filename\":\"main.tf\"}]")));

        UUID workspaceId = UUID.randomUUID();
        String secret = Base64.getEncoder().encodeToString(workspaceId.toString().getBytes(StandardCharsets.UTF_8));
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl(vcsBaseUrl());

        String payload = """
                {
                  "action": "opened",
                  "number": 1,
                  "repository": {"owner": {"login": "victim"}, "name": "repo"},
                  "pull_request": {
                    "user": {"login": "attacker"},
                    "head": {"sha": "abc123", "ref": "feature"},
                    "base": {"ref": "main"},
                    "url": "%s/repos/victim/repo/pulls/1"
                  }
                }
                """.formatted(attackerBaseUrl());

        WebhookResult result = gitHubService.processWebhook(
                payload,
                Map.of("x-github-event", "pull_request", "x-hub-signature-256", signHmacSha256(secret, payload)),
                secret,
                vcs,
                "https://github.com/victim/repo");

        assertTrue(result.isValid());
        assertThat(result.getFileChanges()).containsExactly("main.tf");
        vcsServer.verify(1, getRequestedFor(urlEqualTo("/repos/victim/repo/pulls/1/files?per_page=100")));
        assertThat(attackerServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void githubV1IssueCommentBlocksAttackerPullRequestUrl() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("scoped-token");
        GitHubWebhookService gitHubService = new GitHubWebhookService(objectMapper, tokenService);
        gitHubService.setWebhookRestTemplate(hardenedRestTemplate);

        vcsServer.stubFor(get(urlEqualTo("/repos/victim/repo/pulls/1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"head\":{\"sha\":\"abc123\",\"ref\":\"feature\"},\"base\":{\"ref\":\"main\"}}")));
        vcsServer.stubFor(get(urlEqualTo("/repos/victim/repo/pulls/1/files?per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"filename\":\"main.tf\"}]")));

        UUID workspaceId = UUID.randomUUID();
        String secret = Base64.getEncoder().encodeToString(workspaceId.toString().getBytes(StandardCharsets.UTF_8));
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl(vcsBaseUrl());

        String payload = """
                {
                  "action": "created",
                  "repository": {"owner": {"login": "victim"}, "name": "repo"},
                  "comment": {"id": 99, "body": "terrakube plan", "user": {"login": "attacker"}},
                  "issue": {
                    "number": 1,
                    "pull_request": {
                      "url": "%s/repos/victim/repo/pulls/1"
                    }
                  }
                }
                """.formatted(attackerBaseUrl());

        WebhookResult result = gitHubService.processWebhook(
                payload,
                Map.of("x-github-event", "issue_comment", "x-hub-signature-256", signHmacSha256(secret, payload)),
                secret,
                vcs,
                "https://github.com/victim/repo");

        assertTrue(result.isValid());
        assertThat(result.getFileChanges()).containsExactly("main.tf");
        vcsServer.verify(1, getRequestedFor(urlEqualTo("/repos/victim/repo/pulls/1")));
        assertThat(attackerServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void githubPrFilesPaginationBlocksOffOriginLinkNextHeader() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("secret-github-token");

        GitHubWebhookService gitHubService = new GitHubWebhookService(objectMapper, tokenService);
        gitHubService.setWebhookRestTemplate(hardenedRestTemplate);

        vcsServer.stubFor(get(urlEqualTo("/repos/victim/repo/pulls/1/files?per_page=100"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<" + attackerBaseUrl() + "/steal>; rel=\"next\"")
                        .withBody("[{\"filename\":\"main.tf\"}]")));

        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl(vcsBaseUrl());

        List<String> files = gitHubService.fetchPrFileChanges(
                vcs, "https://github.com/victim/repo", vcsBaseUrl() + "/repos/victim/repo/pulls/1/files");

        assertThat(files).containsExactly("main.tf");
        vcsServer.verify(1, getRequestedFor(urlEqualTo("/repos/victim/repo/pulls/1/files?per_page=100")));
        assertThat(attackerServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void githubV2HelpersBlockAttackerUrls() {
        TokenService tokenService = mock(TokenService.class);
        GitHubWebhookService gitHubService = new GitHubWebhookService(objectMapper, tokenService);
        gitHubService.setWebhookRestTemplate(hardenedRestTemplate);

        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl(vcsBaseUrl());

        assertThat(gitHubService.fetchPrFileChanges(
                vcs, "https://github.com/victim/repo", attackerBaseUrl() + "/repos/victim/repo/pulls/1/files"))
                .isEmpty();
        assertFalse(gitHubService.resolvePrDetails(
                vcs, "https://github.com/victim/repo", attackerBaseUrl() + "/repos/victim/repo/pulls/1",
                new WebhookResult()));

        assertThat(attackerServer.getAllServeEvents()).isEmpty();
        verifyNoInteractions(tokenService);
    }

    @Test
    void githubV1AppUsesWorkspaceSourceOwnerNotForgedPayloadOwner() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("scoped-token");

        GitHubWebhookService gitHubService = new GitHubWebhookService(objectMapper, tokenService);
        gitHubService.setWebhookRestTemplate(hardenedRestTemplate);

        vcsServer.stubFor(get(urlEqualTo("/repos/victim-org/victim-repo/pulls/7/files?per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"filename\":\"infra/main.tf\"}]")));

        UUID workspaceId = UUID.randomUUID();
        String secret = Base64.getEncoder().encodeToString(workspaceId.toString().getBytes(StandardCharsets.UTF_8));
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl(vcsBaseUrl());

        String payload = """
                {
                  "action": "opened",
                  "number": 7,
                  "repository": {"owner": {"login": "other-org"}, "name": "other-repo"},
                  "pull_request": {
                    "user": {"login": "attacker"},
                    "head": {"sha": "abc123", "ref": "feature"},
                    "base": {"ref": "main"},
                    "url": "%s/repos/victim-org/victim-repo/pulls/7"
                  }
                }
                """.formatted(vcsBaseUrl());

        WebhookResult result = gitHubService.processWebhook(
                payload,
                Map.of("x-github-event", "pull_request", "x-hub-signature-256", signHmacSha256(secret, payload)),
                secret,
                vcs,
                "https://github.com/victim-org/victim-repo.git");

        assertThat(result.getFileChanges()).containsExactly("infra/main.tf");
        ArgumentCaptor<String[]> ownerRepoCaptor = ArgumentCaptor.forClass(String[].class);
        verify(tokenService).getAccessToken(ownerRepoCaptor.capture(), org.mockito.ArgumentMatchers.eq(vcs));
        assertThat(ownerRepoCaptor.getValue()).containsExactly("victim-org", "victim-repo");
    }

    @Test
    void bitbucketV1EventsBlockAttackerDiffUrl() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.BITBUCKET);
        vcs.setApiUrl(vcsBaseUrl());
        vcs.setAccessToken("secret-bitbucket-token");
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setVcs(vcs);
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));

        BitBucketWebhookService bitbucketService = new BitBucketWebhookService(
                workspaceRepository, objectMapper, WebClient.builder());
        String secret = Base64.getEncoder().encodeToString(workspaceId.toString().getBytes(StandardCharsets.UTF_8));

        String pushPayload = """
                {
                  "push": {
                    "changes": [{
                      "new": {
                        "type": "branch",
                        "name": "main",
                        "target": {"type": "commit", "hash": "abc123", "author": {"raw": "User <user@example.com>"}}
                      },
                      "links": {"diff": {"href": "%s/steal/push"}}
                    }]
                  }
                }
                """.formatted(attackerBaseUrl());

        WebhookResult pushResult = bitbucketService.processWebhook(
                pushPayload,
                Map.of("x-event-key", "repo:push", "x-hub-signature", signBitbucketHmacSha256(secret, pushPayload)),
                secret);
        assertTrue(pushResult.isValid());
        assertThat(pushResult.getFileChanges()).isEmpty();

        String prPayload = """
                {
                  "pullrequest": {
                    "id": 15,
                    "source": {"branch": {"name": "feature"}, "commit": {"hash": "abc123"}},
                    "destination": {"branch": {"name": "main"}},
                    "links": {"diff": {"href": "%s/steal/pr"}}
                  }
                }
                """.formatted(attackerBaseUrl());

        WebhookResult prResult = bitbucketService.processWebhook(
                prPayload,
                Map.of("x-event-key", "pullrequest:created", "x-hub-signature", signBitbucketHmacSha256(secret, prPayload)),
                secret);
        assertTrue(prResult.isValid());
        assertThat(prResult.getFileChanges()).isEmpty();

        String commentPayload = """
                {
                  "comment": {"id": 99, "content": {"raw": "terrakube plan"}, "user": {"display_name": "Attacker"}},
                  "pullrequest": {
                    "id": 15,
                    "source": {"branch": {"name": "feature"}, "commit": {"hash": "abc123"}},
                    "destination": {"branch": {"name": "main"}},
                    "links": {"diff": {"href": "%s/steal/comment"}}
                  }
                }
                """.formatted(attackerBaseUrl());

        WebhookResult commentResult = bitbucketService.processWebhook(
                commentPayload,
                Map.of("x-event-key", "pullrequest:comment_created",
                        "x-hub-signature", signBitbucketHmacSha256(secret, commentPayload)),
                secret);
        assertTrue(commentResult.isValid());
        assertThat(commentResult.getFileChanges()).isEmpty();

        assertThat(attackerServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void bitbucketBlocksCrossOriginRedirectButAllowsSameOriginRedirect() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.BITBUCKET);
        vcs.setApiUrl(vcsBaseUrl());
        vcs.setAccessToken("secret-bitbucket-token");
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setVcs(vcs);
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));

        BitBucketWebhookService bitbucketService = new BitBucketWebhookService(
                workspaceRepository, objectMapper, WebClient.builder());
        String secret = Base64.getEncoder().encodeToString(workspaceId.toString().getBytes(StandardCharsets.UTF_8));

        vcsServer.stubFor(get(urlEqualTo("/repositories/org/repo/pullrequests/1/diff"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", attackerBaseUrl() + "/steal")));

        String crossOriginPayload = """
                {
                  "pullrequest": {
                    "id": 1,
                    "source": {"branch": {"name": "feature"}, "commit": {"hash": "abc123"}},
                    "destination": {"branch": {"name": "main"}},
                    "links": {"diff": {"href": "%s/repositories/org/repo/pullrequests/1/diff"}}
                  }
                }
                """.formatted(vcsBaseUrl());

        WebhookResult crossOriginResult = bitbucketService.processWebhook(
                crossOriginPayload,
                Map.of("x-event-key", "pullrequest:created",
                        "x-hub-signature", signBitbucketHmacSha256(secret, crossOriginPayload)),
                secret);

        assertThat(crossOriginResult.getFileChanges()).isEmpty();
        assertThat(attackerServer.getAllServeEvents()).isEmpty();

        vcsServer.stubFor(get(urlEqualTo("/repositories/org/repo/pullrequests/2/diff"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/repositories/org/repo/diff/a..b")));
        vcsServer.stubFor(get(urlEqualTo("/repositories/org/repo/diff/a..b"))
                .willReturn(aResponse().withStatus(200).withBody("diff --git a/infra/main.tf b/infra/main.tf\n")));

        String sameOriginPayload = """
                {
                  "pullrequest": {
                    "id": 2,
                    "source": {"branch": {"name": "feature"}, "commit": {"hash": "abc123"}},
                    "destination": {"branch": {"name": "main"}},
                    "links": {"diff": {"href": "%s/repositories/org/repo/pullrequests/2/diff"}}
                  }
                }
                """.formatted(vcsBaseUrl());

        WebhookResult sameOriginResult = bitbucketService.processWebhook(
                sameOriginPayload,
                Map.of("x-event-key", "pullrequest:created",
                        "x-hub-signature", signBitbucketHmacSha256(secret, sameOriginPayload)),
                secret);

        assertThat(sameOriginResult.getFileChanges()).containsExactly("infra/main.tf");
    }

    @Test
    void webhookRestTemplateBlocksCrossOriginRedirectAndAllowsSameOriginRedirect() {
        vcsServer.stubFor(get(urlEqualTo("/cross-origin"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", attackerBaseUrl() + "/steal")));
        vcsServer.stubFor(get(urlEqualTo("/same-origin"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/final")));
        vcsServer.stubFor(get(urlEqualTo("/final"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer secret-token");
        ResponseEntity<String> blocked = hardenedRestTemplate.exchange(
                vcsBaseUrl() + "/cross-origin", HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers),
                String.class);

        assertEquals(302, blocked.getStatusCode().value());
        assertThat(attackerServer.getAllServeEvents()).isEmpty();

        ResponseEntity<String> allowed = hardenedRestTemplate.exchange(
                vcsBaseUrl() + "/same-origin", HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers),
                String.class);

        assertEquals(200, allowed.getStatusCode().value());
        assertEquals("ok", allowed.getBody());
    }

    @Test
    void githubV1FailsClosedWhenWorkspaceSourceIsMissing() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        GitHubWebhookService gitHubService = new GitHubWebhookService(objectMapper, tokenService);
        gitHubService.setWebhookRestTemplate(hardenedRestTemplate);

        UUID workspaceId = UUID.randomUUID();
        String secret = Base64.getEncoder().encodeToString(workspaceId.toString().getBytes(StandardCharsets.UTF_8));
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl(vcsBaseUrl());

        String payload = """
                {
                  "action": "opened",
                  "number": 1,
                  "repository": {"owner": {"login": "victim-org"}, "name": "victim-repo"},
                  "pull_request": {
                    "user": {"login": "attacker"},
                    "head": {"sha": "abc123", "ref": "feature"},
                    "base": {"ref": "main"},
                    "url": "%s/repos/victim-org/victim-repo/pulls/1"
                  }
                }
                """.formatted(vcsBaseUrl());

        WebhookResult result = gitHubService.processWebhook(
                payload,
                Map.of("x-github-event", "pull_request", "x-hub-signature-256", signHmacSha256(secret, payload)),
                secret,
                vcs,
                null);

        assertFalse(result.isValid());
        verifyNoInteractions(tokenService);
    }

    @Test
    void githubBlocksSameOriginCrossRepoPullRequestUrlsInV1AndV2() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("scoped-token");
        GitHubWebhookService gitHubService = new GitHubWebhookService(objectMapper, tokenService);
        gitHubService.setWebhookRestTemplate(hardenedRestTemplate);

        vcsServer.stubFor(get(urlEqualTo("/repos/victim/repo/pulls/1/files?per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"filename\":\"main.tf\"}]")));

        UUID workspaceId = UUID.randomUUID();
        String secret = Base64.getEncoder().encodeToString(workspaceId.toString().getBytes(StandardCharsets.UTF_8));
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl(vcsBaseUrl());

        // V1: payload pull_request.url points at another repo on the same VCS host; v1 builds URL from workspaceSource.
        String v1Payload = """
                {
                  "action": "opened",
                  "number": 1,
                  "repository": {"owner": {"login": "other-org"}, "name": "private-repo"},
                  "pull_request": {
                    "user": {"login": "attacker"},
                    "head": {"sha": "abc123", "ref": "feature"},
                    "base": {"ref": "main"},
                    "url": "%s/repos/other-org/private-repo/pulls/1"
                  }
                }
                """.formatted(vcsBaseUrl());

        WebhookResult v1Result = gitHubService.processWebhook(
                v1Payload,
                Map.of("x-github-event", "pull_request", "x-hub-signature-256", signHmacSha256(secret, v1Payload)),
                secret,
                vcs,
                "https://github.com/victim/repo");

        assertTrue(v1Result.isValid());
        assertThat(v1Result.getFileChanges()).containsExactly("main.tf");
        vcsServer.verify(0, getRequestedFor(urlPathMatching("/repos/other-org/.*")));

        // V2: fetchPrFileChanges & resolvePrDetails reject same-origin URLs outside the workspace repo path.
        assertThat(gitHubService.fetchPrFileChanges(
                vcs, "https://github.com/victim/repo", vcsBaseUrl() + "/repos/other-org/private-repo/pulls/1/files"))
                .isEmpty();
        assertFalse(gitHubService.resolvePrDetails(
                vcs, "https://github.com/victim/repo", vcsBaseUrl() + "/repos/other-org/private-repo/pulls/1",
                new WebhookResult()));
        vcsServer.verify(0, getRequestedFor(urlPathMatching("/repos/other-org/.*")));
    }

    @Test
    void redirectStrategyAllowsSameHostHttpsUpgradeAndBlocksHttpsDowngrade() {
        var strategy = new WebhookHttpClientConfig.SameOriginRedirectStrategy();
        var request = new org.apache.hc.core5.http.message.BasicHttpRequest("GET", "/");
        var context = new org.apache.hc.client5.http.protocol.HttpClientContext();

        var httpHost = new org.apache.hc.core5.http.HttpHost("http", "git.corp", -1);
        var httpsHost = new org.apache.hc.core5.http.HttpHost("https", "git.corp", -1);
        var otherHost = new org.apache.hc.core5.http.HttpHost("https", "evil.corp", -1);

        assertTrue(strategy.isRedirectAllowed(httpHost, httpsHost, request, context));
        assertFalse(strategy.isRedirectAllowed(httpsHost, httpHost, request, context));
        assertFalse(strategy.isRedirectAllowed(httpsHost, otherHost, request, context));
    }
}
