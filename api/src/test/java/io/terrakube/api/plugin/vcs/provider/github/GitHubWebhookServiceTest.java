package io.terrakube.api.plugin.vcs.provider.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;

public class GitHubWebhookServiceTest {

    private final GitHubWebhookService subject = new GitHubWebhookService(new ObjectMapper(), null);

    @Nested
    class WebhookRunAuthor {

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"name\":\"Octo Cat\",\"email\":\"pusher@example.com\"}",
                "{\"name\":\"Octo Cat\",\"email\":null}",
                "{\"name\":\"Octo Cat\"}"
        })
        void pushUsesSenderLoginRegardlessOfPusherEmail(String pusher) throws Exception {
            String payload = """
                    {
                      "ref": "refs/heads/main",
                      "pusher": %s,
                      "sender": {"login": "octocat"},
                      "head_commit": {"id": "abc123"},
                      "commits": []
                    }
                    """.formatted(pusher);

            WebhookResult result = processWebhook("push", payload);

            assertTrue(result.isValid());
            assertEquals("octocat", result.getCreatedBy());
        }

        @Test
        void pullRequestKeepsAuthorLoginWhenSenderDiffers() throws Exception {
            String payload = """
                    {
                      "action": "synchronize",
                      "number": 42,
                      "repository": {"owner": {"login": "owner"}, "name": "repo"},
                      "sender": {"login": "another-user"},
                      "pull_request": {
                        "user": {"login": "octocat"},
                        "head": {"sha": "abc123", "ref": "feature"},
                        "url": "https://api.github.com/repos/owner/repo/pulls/42"
                      }
                    }
                    """;

            WebhookResult result = processWebhook("pull_request", payload);

            assertTrue(result.isValid());
            assertEquals("octocat", result.getCreatedBy());
        }

        private WebhookResult processWebhook(String event, String payload) throws Exception {
            String token = Base64.getEncoder().encodeToString("workspace-id".getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

            return subject.processWebhook(payload, Map.of(
                    "x-github-event", event,
                    "x-hub-signature-256", signature), token, null);
        }
    }

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

    @Nested
    class PrCommentJson {

        WireMockServer wireMockServer;
        GitHubWebhookService subjectWithRestTemplate;
        Job job;

        @BeforeEach
        void setUp() throws Exception {
            wireMockServer = new WireMockServer(options().dynamicPort());
            wireMockServer.start();
            TokenService tokenService = mock(TokenService.class);
            when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("test-token");
            subjectWithRestTemplate = new GitHubWebhookService(new ObjectMapper(), tokenService);
            subjectWithRestTemplate.setWebhookRestTemplate(
                    new RestTemplate(new HttpComponentsClientHttpRequestFactory()));

            Vcs vcs = new Vcs();
            vcs.setApiUrl("http://localhost:" + wireMockServer.port());
            Workspace workspace = new Workspace();
            workspace.setSource("https://github.com/owner/repo");
            workspace.setVcs(vcs);
            workspace.setName("test-ws");
            job = new Job();
            job.setWorkspace(workspace);
            job.setPrNumber(42);
        }

        @AfterEach
        void tearDown() {
            wireMockServer.stop();
        }

        @ParameterizedTest
        @MethodSource("commentBodies")
        void createsCommentWithValidUtf8Json(String markdown) throws Exception {
            wireMockServer.stubFor(post(urlPathMatching("/repos/owner/repo/issues/42/comments"))
                    .willReturn(aResponse().withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":12345}")));

            assertEquals("12345", subjectWithRestTemplate.postPrComment(job, markdown));

            assertCommentBody(markdown);
        }

        @ParameterizedTest
        @MethodSource("commentBodies")
        void updatesCommentWithValidUtf8Json(String markdown) throws Exception {
            wireMockServer.stubFor(patch(urlPathMatching("/repos/owner/repo/issues/comments/12345"))
                    .willReturn(aResponse().withStatus(200)));

            assertTrue(subjectWithRestTemplate.updatePrComment(job, "12345", markdown));

            assertCommentBody(markdown);
        }

        @Test
        void doesNotAddContentTypeWhenResolvingPrDetails() {
            String path = "/repos/owner/repo/pulls/42";
            wireMockServer.stubFor(get(urlPathMatching(path))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{\"head\":{\"sha\":\"abc123\",\"ref\":\"main\"}}")));
            Workspace workspace = job.getWorkspace();

            assertTrue(subjectWithRestTemplate.resolvePrDetails(workspace.getVcs(), workspace.getSource(),
                    workspace.getVcs().getApiUrl() + path, new WebhookResult()));

            assertNull(wireMockServer.getAllServeEvents().getFirst().getRequest()
                    .getHeader(HttpHeaders.CONTENT_TYPE));
        }

        @Test
        void doesNotAddJsonContentTypeWhenFetchingPrFiles() {
            String path = "/repos/owner/repo/pulls/42/files";
            wireMockServer.stubFor(get(urlPathMatching(path))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
            Workspace workspace = job.getWorkspace();

            assertTrue(subjectWithRestTemplate.fetchPrFileChanges(workspace.getVcs(), workspace.getSource(),
                    workspace.getVcs().getApiUrl() + path).isEmpty());

            String contentType = wireMockServer.getAllServeEvents().getFirst().getRequest()
                    .getHeader(HttpHeaders.CONTENT_TYPE);
            assertTrue(contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(
                    MediaType.parseMediaType(contentType)));
        }

        private static Stream<String> commentBodies() {
            StringBuilder markdown = new StringBuilder(
                    "\u001b[32m+ resource\u001b[0m\nPlan: 1 to add, 0 to change, 0 to destroy.\n\"quoted\" \\ path\n");
            for (char control = 0; control < 0x20; control++) {
                markdown.append(control);
            }
            return Stream.of(markdown.toString(),
                    "✅ Plan: 1 to add. 🟢 create 🔵 update ⚪ unknown", "café 中文 日本語 🚀");
        }

        private void assertCommentBody(String markdown) throws Exception {
            assertEquals(1, wireMockServer.getAllServeEvents().size());
            var request = wireMockServer.getAllServeEvents().getFirst().getRequest();
            assertEquals(markdown, new ObjectMapper().readTree(request.getBody()).path("body").asText());
            assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                    MediaType.parseMediaType(request.getHeader(HttpHeaders.CONTENT_TYPE))));
        }
    }

    @Nested
    class SendCommitStatus {

        WireMockServer wireMockServer;
        TokenService tokenService;
        GitHubWebhookService subjectWithRestTemplate;

        @BeforeEach
        void setUp() throws Exception {
            wireMockServer = new WireMockServer(options().dynamicPort());
            wireMockServer.start();
            tokenService = mock(TokenService.class);
            when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("test-token");
            subjectWithRestTemplate = new GitHubWebhookService(new ObjectMapper(), tokenService);
            subjectWithRestTemplate.setWebhookRestTemplate(new RestTemplate());

            wireMockServer.stubFor(post(urlPathMatching("/repos/owner/repo/statuses/.*"))
                    .willReturn(aResponse().withStatus(201)));
        }

        @AfterEach
        void tearDown() {
            wireMockServer.stop();
        }

        @Test
        void neverCallsThePullsStatusesEndpoint() {
            Vcs vcs = new Vcs();
            vcs.setApiUrl("http://localhost:" + wireMockServer.port());
            Organization organization = new Organization();
            organization.setName("test-org");
            Workspace workspace = new Workspace();
            workspace.setSource("https://github.com/owner/repo");
            workspace.setVcs(vcs);
            workspace.setOrganization(organization);
            workspace.setName("test-ws");
            Job job = new Job();
            job.setWorkspace(workspace);
            job.setCommitId("abc123");
            job.setStatus(JobStatus.completed);

            subjectWithRestTemplate.sendCommitStatus(job, JobStatus.completed, null);

            wireMockServer.verify(1, postRequestedFor(urlPathMatching("/repos/owner/repo/statuses/.*")));
            wireMockServer.verify(0, postRequestedFor(urlPathMatching(".*/pulls/.*/statuses.*")));
        }
    }
}
