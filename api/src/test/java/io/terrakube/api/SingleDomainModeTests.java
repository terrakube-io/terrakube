package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import io.terrakube.api.plugin.state.RemoteTfeService;
import io.terrakube.api.plugin.token.dynamic.OpenIdConfigurationController;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabTokenService;

/**
 * Single-domain mode: TerrakubePublicUrl set, TerrakubeHostname unset. Discovery advertises every
 * service under /api on the shared host and generated URLs carry the canonical prefix.
 */
@TestPropertySource(properties = {
    "io.terrakube.public-url=https://app.example.com",
    "io.terrakube.hostname="
})
class SingleDomainModeTests extends ServerApplicationTests {

    @Autowired
    GitHubWebhookService gitHubWebhookService;

    @Autowired
    GitLabTokenService gitLabTokenService;

    @Autowired
    RemoteTfeService remoteTfeService;

    @Autowired
    OpenIdConfigurationController openIdConfigurationController;

    @Test
    void discoveryAdvertisesEveryServiceUnderApi() {
        given().when().get("/.well-known/terraform.json").then().statusCode(200)
                .body("size()", equalTo(6))
                .body("'state.v2'", equalTo("/api/remote/state/v2/"))
                .body("'tfe.v2'", equalTo("/api/remote/tfe/v2/"))
                .body("'tfe.v2.1'", equalTo("/api/remote/tfe/v2/"))
                .body("'modules.v1'", equalTo("/api/private-registry/terraform/modules/v1/"))
                .body("'providers.v1'", equalTo("/api/private-registry/terraform/providers/v1/"))
                .body("'login.v1'.client", equalTo("example-app"))
                .body("'login.v1'.ports", contains(10000, 10001));
    }

    @Test
    void discoveryIsAlsoServedUnderCanonicalAlias() {
        given().when().get("/api/.well-known/terraform.json").then().statusCode(200)
                .body("'tfe.v2'", equalTo("/api/remote/tfe/v2/"));
    }

    @Test
    void generatedUrlsUseThePublicApiBase() {
        assertEquals("https://app.example.com/api", ReflectionTestUtils.getField(gitHubWebhookService, "apiBaseUrl"));
        assertEquals("https://app.example.com/api", ReflectionTestUtils.getField(gitLabTokenService, "apiBaseUrl"));
        assertEquals("https://app.example.com/api", ReflectionTestUtils.getField(remoteTfeService, "apiBaseUrl"));
        assertEquals("/api", ReflectionTestUtils.getField(remoteTfeService, "apiPathPrefix"));
    }

    @Test
    void dynamicCredentialIssuerFallsBackToPublicHost() {
        ReflectionTestUtils.setField(openIdConfigurationController, "openIdConfiguration", null);
        given().when().get("/.well-known/openid-configuration").then().statusCode(200)
                .body("issuer", equalTo("https://app.example.com"))
                .body("jwks_uri", equalTo("https://app.example.com/.well-known/jwks"));
    }
}
