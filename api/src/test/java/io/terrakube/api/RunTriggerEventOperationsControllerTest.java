package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventTransactions;
import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunTriggerEventOperationsControllerTest extends ServerApplicationTests {

    // The "simple" demo org; the teams below are created for each test (see CascadeOperationTeams).
    private static final UUID SIMPLE_ORG = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");

    @MockitoBean
    RunTriggerEventTransactions transactions;

    @MockitoBean
    RunTriggerEventRepository repository;

    @BeforeEach
    void createTeams() {
        CascadeOperationTeams.create(teamRepository, organizationRepository, SIMPLE_ORG);
    }

    @AfterEach
    void removeTeams() {
        CascadeOperationTeams.delete(teamRepository, SIMPLE_ORG);
    }

    private static final String ORG_EVENTS = "/cascade/v1/organization/" + SIMPLE_ORG + "/trigger-event";

    private RunTriggerEvent failedEvent(UUID eventId) {
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
        return event;
    }

    @Test
    void replayRearmsAFailedEventInTheOrganization() {
        UUID eventId = UUID.randomUUID();
        when(repository.findOrganizationId(eventId)).thenReturn(Optional.of(SIMPLE_ORG));
        when(transactions.rearmForRetry(eventId)).thenReturn(true);

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().post(ORG_EVENTS + "/" + eventId + "/replay")
                .then().statusCode(HttpStatus.OK.value())
                .body("eventId", equalTo(eventId.toString()))
                .body("rearmed", equalTo(true));
    }

    @Test
    void replayOfANonFailedEventIsANoOpNotAnError() {
        UUID eventId = UUID.randomUUID();
        when(repository.findOrganizationId(eventId)).thenReturn(Optional.of(SIMPLE_ORG));
        when(transactions.rearmForRetry(eventId)).thenReturn(false);

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(ORG_EVENTS + "/" + eventId + "/replay")
                .then().statusCode(HttpStatus.OK.value())
                .body("rearmed", equalTo(false));
    }

    @Test
    void replayNeedsManageJobInTheOrganization() {
        UUID eventId = UUID.randomUUID();
        when(repository.findOrganizationId(eventId)).thenReturn(Optional.of(SIMPLE_ORG));

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.VIEWERS))
                .when().post(ORG_EVENTS + "/" + eventId + "/replay")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
        verify(transactions, never()).rearmForRetry(eventId);
    }

    @Test
    void anEventFromAnotherOrganizationIsNotFound() {
        UUID eventId = UUID.randomUUID();
        when(repository.findOrganizationId(eventId)).thenReturn(Optional.of(UUID.randomUUID()));

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().post(ORG_EVENTS + "/" + eventId + "/replay")
                .then().statusCode(HttpStatus.NOT_FOUND.value());
        verify(transactions, never()).rearmForRetry(eventId);
    }

    @Test
    void anOrganizationListNeedsManageJobInThatOrganization() {
        UUID eventId = UUID.randomUUID();
        when(repository.findByStatusAndOrganizationOrderByCreatedDateDesc(
                eq(RunTriggerEventStatus.FAILED), eq(SIMPLE_ORG), any(Pageable.class)))
                .thenReturn(List.of(failedEvent(eventId)));

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().get(ORG_EVENTS)
                .then().statusCode(HttpStatus.OK.value())
                .body("[0].id", equalTo(eventId.toString()))
                .body("[0].jobId", equalTo(321))
                .body("[0].workspaceName", equalTo("drifted-workspace"))
                .body("[0].attemptCount", equalTo(5))
                .body("[0].organizationId", equalTo(SIMPLE_ORG.toString()))
                .body("[0].organizationName", equalTo("simple"));

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.VIEWERS))
                .when().get(ORG_EVENTS)
                .then().statusCode(HttpStatus.FORBIDDEN.value());

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().get("/cascade/v1/organization/" + UUID.randomUUID() + "/trigger-event")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void theInstanceWideListIsForInstanceAdminsOnly() {
        UUID eventId = UUID.randomUUID();
        when(repository.findByStatusOrderByCreatedDateDesc(eq(RunTriggerEventStatus.FAILED), any(Pageable.class)))
                .thenReturn(List.of(failedEvent(eventId)));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get("/cascade/v1/trigger-event")
                .then().statusCode(HttpStatus.OK.value())
                .body("[0].id", equalTo(eventId.toString()));

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().get("/cascade/v1/trigger-event")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }
}
