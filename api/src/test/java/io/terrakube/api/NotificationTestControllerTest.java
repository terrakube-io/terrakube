package io.terrakube.api;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class NotificationTestControllerTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    NotificationConfigurationRepository notificationConfigurationRepository;
    @Autowired
    OrganizationRepository organizationRepository;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    private NotificationConfiguration savedWebhookConfig() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("Send Test Config");
        configuration.setOrganization(organization);
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("http://localhost:" + wireMockServer.port() + "/hook");
        configuration.setActive(true);
        return notificationConfigurationRepository.saveAndFlush(configuration);
    }

    @Test
    void sendTestAgainstASavedConfigurationSucceeds() {
        // Regression test: configuration.getOrganization() is a lazy proxy once re-fetched by
        // sendTest's own repository lookup (a fresh request, no longer the same persistence
        // context savedWebhookConfig() used to create it) - without a transaction open around
        // that fetch, this threw LazyInitializationException instead of ever reaching the
        // destination. The transaction closes before the outbound HTTP call, unlike the
        // configuration's own repository lookup.
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        NotificationConfiguration configuration = savedWebhookConfig();

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .post("/notification/v1/configuration/" + configuration.getId() + "/test")
                .then()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void sendTestReturnsBadGatewayWhenDestinationRejectsTheRequest() {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(500)));
        NotificationConfiguration configuration = savedWebhookConfig();

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .post("/notification/v1/configuration/" + configuration.getId() + "/test")
                .then()
                .statusCode(HttpStatus.BAD_GATEWAY.value());
    }

    @Test
    void sendTestForAnUnknownConfigurationIsForbidden() {
        // @PreAuthorize's hasManagePermission resolves the config to check permission before the
        // controller body ever runs; it fails closed (403) for anything it can't resolve, missing
        // or not, so the controller's own "if configuration == null" 404 branch is unreachable
        // from this endpoint - this asserts the actual (and reasonable, avoids leaking which IDs
        // exist) behavior rather than the never-hit branch.
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .post("/notification/v1/configuration/" + UUID.randomUUID() + "/test")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void sendAdHocTestSucceedsAgainstAnUnsavedConfiguration() {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(200)));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/json")
                .body("{\"channelType\":\"WEBHOOK\",\"destinationUrl\":\"http://localhost:" + wireMockServer.port()
                        + "/hook\"}")
                .when()
                .post("/notification/v1/organization/" + ORGANIZATION_ID + "/configuration/test")
                .then()
                .statusCode(HttpStatus.OK.value());
    }
}
