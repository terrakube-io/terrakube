package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventTransactions;
import io.terrakube.api.repository.RunTriggerEventRepository;
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
        Job job = new Job();
        job.setId(321);
        job.setWorkspace(workspace);
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
                .body("[0].attemptCount", equalTo(5));
    }
}
