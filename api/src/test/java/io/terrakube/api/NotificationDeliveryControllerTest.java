package io.terrakube.api;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.rs.workspace.Workspace;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class NotificationDeliveryControllerTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @Autowired
    NotificationOutboxRepository notificationOutboxRepository;
    @Autowired
    NotificationConfigurationRepository notificationConfigurationRepository;
    @Autowired
    JobRepository jobRepository;
    @Autowired
    OrganizationRepository organizationRepository;
    @Autowired
    WorkspaceRepository workspaceRepository;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    private NotificationOutbox failedDelivery() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();

        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("Retry Test Config");
        configuration.setOrganization(organization);
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("http://localhost:" + wireMockServer.port() + "/hook");
        configuration.setActive(true);
        configuration = notificationConfigurationRepository.saveAndFlush(configuration);

        Job job = new Job();
        job.setStatus(JobStatus.failed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        jobRepository.saveAndFlush(job);

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setJob(job);
        outbox.setConfiguration(configuration);
        outbox.setPayload("{}");
        outbox.setStatus(NotificationOutboxStatus.FAILED);
        outbox.setAttemptCount(3);
        outbox.setLastError("boom");
        return notificationOutboxRepository.saveAndFlush(outbox);
    }

    @Test
    void retryingAFailedDeliveryRearmsItAndDispatchesImmediately() throws InterruptedException {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
        NotificationOutbox outbox = failedDelivery();

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .post("/notification/v1/workspace/" + WORKSPACE_ID + "/deliveries/" + outbox.getId() + "/retry")
                .then()
                .statusCode(HttpStatus.OK.value());

        NotificationOutbox persisted = awaitDeliveryOutcome(outbox.getId());
        org.assertj.core.api.Assertions.assertThat(persisted.getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/hook")));
    }

    // Async dispatch (@Async("notificationDispatchExecutor")) runs on a separate thread pool, so
    // the HTTP response for the retry request returns before delivery actually completes.
    private NotificationOutbox awaitDeliveryOutcome(UUID outboxId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            NotificationOutbox current = notificationOutboxRepository.findById(outboxId).orElseThrow();
            if (current.getStatus() != NotificationOutboxStatus.PENDING
                    && current.getStatus() != NotificationOutboxStatus.SENDING) {
                return current;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("delivery " + outboxId + " did not complete within the wait window");
    }

    @Test
    void retryingANonFailedDeliveryReturnsConflict() {
        NotificationOutbox outbox = failedDelivery();
        outbox.setStatus(NotificationOutboxStatus.SENT);
        notificationOutboxRepository.saveAndFlush(outbox);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .post("/notification/v1/workspace/" + WORKSPACE_ID + "/deliveries/" + outbox.getId() + "/retry")
                .then()
                .statusCode(HttpStatus.CONFLICT.value());
    }

    @Test
    void retryingAnUnknownDeliveryReturnsNotFound() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .post("/notification/v1/workspace/" + WORKSPACE_ID + "/deliveries/" + UUID.randomUUID() + "/retry")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void retryingWithoutWorkspaceAccessIsForbidden() {
        NotificationOutbox outbox = failedDelivery();

        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS")).when()
                .post("/notification/v1/workspace/" + WORKSPACE_ID + "/deliveries/" + outbox.getId() + "/retry")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void recentDeliveriesListsDeliveriesForTheWorkspace() {
        NotificationOutbox outbox = failedDelivery();

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .get("/notification/v1/workspace/" + WORKSPACE_ID + "/deliveries")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("id", org.hamcrest.Matchers.hasItem(outbox.getId().toString()));
    }
}
