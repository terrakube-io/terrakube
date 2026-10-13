package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventTransactions;
import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class RunTriggerEventOperationsControllerTest extends ServerApplicationTests {

    // "simple" org: TERRAKUBE_DEVELOPERS has manage_job there, AZURE_DEVELOPERS does not.
    private static final UUID SIMPLE_ORG = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");

    @MockitoBean
    RunTriggerEventTransactions transactions;

    @MockitoBean
    RunTriggerEventRepository repository;

    @Test
    void replayRequiresAdminGroup() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/run-trigger-events/" + UUID.randomUUID() + "/replay")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void replayRearmsAFailedEvent() {
        UUID eventId = UUID.randomUUID();
        when(transactions.rearmForRetry(eventId)).thenReturn(true);

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/run-trigger-events/" + eventId + "/replay")
                .then().statusCode(HttpStatus.OK.value())
                .body("eventId", equalTo(eventId.toString()))
                .body("rearmed", equalTo(true));
    }

    @Test
    void replayOfANonFailedEventIsANoOpNotAnError() {
        UUID eventId = UUID.randomUUID();
        when(transactions.rearmForRetry(eventId)).thenReturn(false);

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/run-trigger-events/" + eventId + "/replay")
                .then().statusCode(HttpStatus.OK.value())
                .body("rearmed", equalTo(false));
    }

    @Test
    void listRequiresAdminGroup() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().get("/admin/v1/run-trigger-events")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void listReturnsFailedEventsByDefault() {
        UUID eventId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setName("drifted-workspace");
        Organization organization = new Organization();
        organization.setId(SIMPLE_ORG);
        organization.setName("simple");
        Job job = new Job();
        job.setId(321);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        RunTriggerEvent event = new RunTriggerEvent();
        event.setId(eventId);
        event.setJob(job);
        event.setStatus(RunTriggerEventStatus.FAILED);
        event.setAttemptCount(5);
        event.setLastError("workspace graph validation service unreachable");

        when(repository.findByStatusOrderByCreatedDateDesc(eq(RunTriggerEventStatus.FAILED), any(Pageable.class)))
                .thenReturn(List.of(event));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get("/admin/v1/run-trigger-events")
                .then().statusCode(HttpStatus.OK.value())
                .body("[0].id", equalTo(eventId.toString()))
                .body("[0].jobId", equalTo(321))
                .body("[0].workspaceName", equalTo("drifted-workspace"))
                .body("[0].attemptCount", equalTo(5))
                .body("[0].organizationId", equalTo(SIMPLE_ORG.toString()))
                .body("[0].organizationName", equalTo("simple"));
    }

    @Test
    void anOrganizationScopedListNeedsManageJobInThatOrganization() {
        when(repository.findByStatusAndOrganizationOrderByCreatedDateDesc(
                eq(RunTriggerEventStatus.FAILED), eq(SIMPLE_ORG), any(Pageable.class)))
                .thenReturn(List.of());

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .queryParam("organizationId", SIMPLE_ORG)
                .when().get("/admin/v1/run-trigger-events")
                .then().statusCode(HttpStatus.OK.value());

        given().headers("Authorization", "Bearer " + generatePAT("AZURE_DEVELOPERS"))
                .queryParam("organizationId", SIMPLE_ORG)
                .when().get("/admin/v1/run-trigger-events")
                .then().statusCode(HttpStatus.FORBIDDEN.value());

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .queryParam("organizationId", UUID.randomUUID())
                .when().get("/admin/v1/run-trigger-events")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void replayChecksTheOrganizationTheEventBelongsTo() {
        UUID eventId = UUID.randomUUID();
        when(repository.findOrganizationId(eventId)).thenReturn(java.util.Optional.of(SIMPLE_ORG));
        when(transactions.rearmForRetry(eventId)).thenReturn(true);

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/run-trigger-events/" + eventId + "/replay")
                .then().statusCode(HttpStatus.OK.value()).body("rearmed", equalTo(true));

        given().headers("Authorization", "Bearer " + generatePAT("AZURE_DEVELOPERS"))
                .when().post("/admin/v1/run-trigger-events/" + eventId + "/replay")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }
}
