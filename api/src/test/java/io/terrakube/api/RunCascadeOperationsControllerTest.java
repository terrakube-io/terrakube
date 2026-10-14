package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.trigger.RunCascadeCoordinatorService;
import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.repository.RunCascadeRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real HTTP: the access rule (instance admin, or manage_job in the organization in the path), that
 * the cascade or node must belong to that organization, and the request/response mapping, with
 * {@link RunCascadeCoordinatorService} mocked out - its own behavior is
 * {@code RunCascadeCoordinatorServiceTest}'s job, not this class's.
 */
class RunCascadeOperationsControllerTest extends ServerApplicationTests {

    // The "simple" demo org; the teams below are created for each test (see CascadeOperationTeams).
    private static final UUID SIMPLE_ORG = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");
    private static final String BASE = "/cascade/v1/organization/";

    @MockitoBean
    RunCascadeCoordinatorService coordinatorService;

    @MockitoBean
    RunCascadeRepository cascadeRepository;

    @MockitoBean
    RunCascadeNodeRepository nodeRepository;

    private final UUID cascadeId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    @BeforeEach
    void cascadeBelongsToSimpleOrg() {
        CascadeOperationTeams.create(teamRepository, organizationRepository, SIMPLE_ORG);
        when(cascadeRepository.findOrganizationId(cascadeId)).thenReturn(Optional.of(SIMPLE_ORG));
        when(nodeRepository.existsByIdAndCascade_Id(nodeId, cascadeId)).thenReturn(true);
    }

    @AfterEach
    void removeTeams() {
        CascadeOperationTeams.delete(teamRepository, SIMPLE_ORG);
    }

    private RunCascade cascade(UUID id, RunCascadeStatus status) {
        RunCascade cascade = new RunCascade();
        cascade.setId(id);
        cascade.setStatus(status);
        return cascade;
    }

    private Job job(int id) {
        Job job = new Job();
        job.setId(id);
        return job;
    }

    private String cancelUrl(UUID organization) {
        return BASE + organization + "/cascade/" + cascadeId + "/cancel";
    }

    private String nodeUrl(UUID organization, String action) {
        return BASE + organization + "/cascade/" + cascadeId + "/node/" + nodeId + "/" + action;
    }

    @Test
    void anInstanceAdminCanCancelAndGetsTheNewStatus() {
        when(coordinatorService.cancelCascade(cascadeId)).thenReturn(cascade(cascadeId, RunCascadeStatus.CANCELLED));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(cancelUrl(SIMPLE_ORG))
                .then().statusCode(HttpStatus.OK.value())
                .body("cascadeId", equalTo(cascadeId.toString()))
                .body("status", equalTo("CANCELLED"));
    }

    @Test
    void aMemberWithManageJobInTheOrganizationCanOperate() {
        when(coordinatorService.cancelCascade(cascadeId)).thenReturn(cascade(cascadeId, RunCascadeStatus.CANCELLED));
        when(coordinatorService.retryNode(nodeId)).thenReturn(job(42));

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().post(cancelUrl(SIMPLE_ORG))
                .then().statusCode(HttpStatus.OK.value());
        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().post(nodeUrl(SIMPLE_ORG, "retry"))
                .then().statusCode(HttpStatus.OK.value())
                .body("nodeId", equalTo(nodeId.toString()))
                .body("status", equalTo("RUNNING"))
                .body("jobId", equalTo(42));
    }

    @Test
    void aMemberWithoutManageJobIsForbidden() {
        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.VIEWERS))
                .when().post(cancelUrl(SIMPLE_ORG))
                .then().statusCode(HttpStatus.FORBIDDEN.value());
        verify(coordinatorService, never()).cancelCascade(cascadeId);
    }

    @Test
    void manageJobInAnotherOrganizationDoesNotGrantAccess() {
        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().post(cancelUrl(UUID.randomUUID()))
                .then().statusCode(HttpStatus.FORBIDDEN.value());
        verify(coordinatorService, never()).cancelCascade(cascadeId);
    }

    @Test
    void aCascadeFromAnotherOrganizationIsNotFoundEvenForAnAuthorizedCaller() {
        UUID otherOrg = UUID.randomUUID();

        // Authorized for otherOrg (instance admin), but the cascade belongs to the simple org.
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(cancelUrl(otherOrg))
                .then().statusCode(HttpStatus.NOT_FOUND.value());
        verify(coordinatorService, never()).cancelCascade(cascadeId);
    }

    @Test
    void aNodeThatIsNotInTheCascadeIsNotFound() {
        UUID strayNode = UUID.randomUUID();

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(BASE + SIMPLE_ORG + "/cascade/" + cascadeId + "/node/" + strayNode + "/retry")
                .then().statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void cancelOfAnUnknownCascadeIs404() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(BASE + SIMPLE_ORG + "/cascade/" + UUID.randomUUID() + "/cancel")
                .then().statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void retryOfANodeInTheWrongStateIsAConflict() {
        when(coordinatorService.retryNode(nodeId))
                .thenThrow(new IllegalStateException("Only a failed or skipped node can be retried, not RUNNING"));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(nodeUrl(SIMPLE_ORG, "retry"))
                .then().statusCode(HttpStatus.CONFLICT.value());
    }

    @Test
    void resumeDispatchesWhenReady() {
        when(coordinatorService.resumeNode(nodeId)).thenReturn(Optional.of(job(43)));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(nodeUrl(SIMPLE_ORG, "resume"))
                .then().statusCode(HttpStatus.OK.value())
                .body("status", equalTo("RUNNING"))
                .body("jobId", equalTo(43));
    }

    @Test
    void resumeLeavesItPausedWhenStillNotReady() {
        when(coordinatorService.resumeNode(nodeId)).thenReturn(Optional.empty());

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post(nodeUrl(SIMPLE_ORG, "resume"))
                .then().statusCode(HttpStatus.OK.value())
                .body("status", equalTo("BLOCKED"))
                .body("jobId", equalTo((Object) null));
    }

    @Test
    void capabilitiesArePerOrganization() {
        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().get(BASE + SIMPLE_ORG + "/capabilities")
                .then().statusCode(HttpStatus.OK.value())
                .body("operate", equalTo(true))
                .body("instanceAdmin", equalTo(false));

        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.VIEWERS))
                .when().get(BASE + SIMPLE_ORG + "/capabilities")
                .then().statusCode(HttpStatus.OK.value())
                .body("operate", equalTo(false));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get(BASE + UUID.randomUUID() + "/capabilities")
                .then().statusCode(HttpStatus.OK.value())
                .body("operate", equalTo(true))
                .body("instanceAdmin", equalTo(true));
    }

    @Test
    void instanceCapabilitiesOnlyReportInstanceAdmin() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get("/cascade/v1/capabilities")
                .then().statusCode(HttpStatus.OK.value()).body("instanceAdmin", equalTo(true));
        given().headers("Authorization", "Bearer " + generatePAT(CascadeOperationTeams.OPERATORS))
                .when().get("/cascade/v1/capabilities")
                .then().statusCode(HttpStatus.OK.value()).body("instanceAdmin", equalTo(false));
    }
}
