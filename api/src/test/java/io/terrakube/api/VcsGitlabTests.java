package io.terrakube.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.mockito.Mockito.when;

public class VcsGitlabTests extends ServerApplicationTests {


    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    @Test
    void gitlabGetIdProject() throws IOException, InterruptedException {
        String simpleSearch="[\n" +
                "    {\n" +
                "        \"id\": 5397249,\n" +
                "        \"path_with_namespace\": \"alfespa17/simple-terraform\"\n" +
                "    }\n" +
                "]";

        stubFor(get(urlPathEqualTo("/projects"))
                .withQueryParam("membership", equalTo("true"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(simpleSearch)));

        GitLabWebhookService gitLabWebhookService = new GitLabWebhookService(new ObjectMapper(), "localhost", "http://localhost", WebClient.builder());

        Assert.isTrue("5397249".equals(gitLabWebhookService.getGitlabProjectId("alfespa17/simple-terraform", "12345", "http://localhost:9999")), "Gitlab project id not found");

        String projectSearch="[\n" +
                "    {\n" +
                "        \"id\": 7138024,\n" +
                "        \"path\": \"simple-terraform\",\n" +
                "        \"path_with_namespace\": \"terraform2745926/simple-terraform\"\n" +
                "    },\n" +
                "    {\n" +
                "        \"id\": 7107040,\n" +
                "        \"path_with_namespace\": \"terraform2745926/test/simple-terraform\"\n" +
                "    }\n" +
                "]";
        stubFor(get(urlPathEqualTo("/projects"))
                .withQueryParam("membership", equalTo("true"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(projectSearch)));

        Assert.isTrue(("7107040".equals(gitLabWebhookService.getGitlabProjectId("terraform2745926/test/simple-terraform", "12345", "http://localhost:9999"))), "Gitlab project id not found");

        stubFor(get(urlPathEqualTo("/projects"))
                .withQueryParam("membership", equalTo("true"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(projectSearch)));

        Assert.isTrue("7138024".equals(gitLabWebhookService.getGitlabProjectId("terraform2745926/simple-terraform", "12345", "http://localhost:9999")), "Gitlab project id not found");

    }
}
