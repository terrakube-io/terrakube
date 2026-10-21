package io.terrakube.api.plugin.vcs.provider.bitbucket;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;

class BitBucketWebhookServiceTest {

    private WireMockServer server;
    private BitBucketWebhookService subject;
    private Job job;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        subject = new BitBucketWebhookService(null, new ObjectMapper());
        subject.setWebhookRestTemplate(new RestTemplate(new HttpComponentsClientHttpRequestFactory()));

        Vcs vcs = new Vcs();
        vcs.setApiUrl("http://localhost:" + server.port());
        vcs.setAccessToken("test-token");
        Workspace workspace = new Workspace();
        workspace.setSource("https://bitbucket.org/owner/repo");
        workspace.setName("test-ws");
        workspace.setVcs(vcs);
        job = new Job();
        job.setWorkspace(workspace);
        job.setPrNumber(42);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"✅ Plan: 1 to add. 🟢 create 🔵 update ⚪ unknown", "café 中文 日本語 🚀"})
    void preservesUnicodeWhenCreatingComment(String markdown) throws Exception {
        server.stubFor(post(urlEqualTo("/repositories/owner/repo/pullrequests/42/comments"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":12345}")));

        assertEquals("12345", subject.postPrComment(job, markdown));

        assertUtf8JsonBody(markdown);
    }

    @ParameterizedTest
    @ValueSource(strings = {"✅ Plan: 1 to add. 🟢 create 🔵 update ⚪ unknown", "café 中文 日本語 🚀"})
    void preservesUnicodeWhenUpdatingComment(String markdown) throws Exception {
        server.stubFor(put(urlEqualTo("/repositories/owner/repo/pullrequests/42/comments/12345"))
                .willReturn(aResponse().withStatus(200)));

        assertTrue(subject.updatePrComment(job, "12345", markdown));

        assertUtf8JsonBody(markdown);
    }

    private void assertUtf8JsonBody(String markdown) throws Exception {
        assertEquals(1, server.getAllServeEvents().size());
        var request = server.getAllServeEvents().getFirst().getRequest();
        assertEquals(markdown, new ObjectMapper().readTree(request.getBody()).path("content").path("raw").asText());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(request.getHeader(HttpHeaders.CONTENT_TYPE))));
    }
}
