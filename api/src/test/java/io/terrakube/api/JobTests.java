package io.terrakube.api;

import org.apache.calcite.avatica.util.Base64;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

class JobTests extends ServerApplicationTests {

    Workspace workspace;
    Organization organization;

    @BeforeAll
    public void setupSuite() {
        organization = organizationRepository.getReferenceById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8"));

        stubFor(post(urlPathEqualTo("/api/v1/terraform-rs"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.ACCEPTED.value())
                        .withBody("")));
}

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();

        workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        workspace.setSource("https://github.com/AzBuilder/terrakube-docker-compose.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.2.5");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.save(workspace);
    }

    @AfterEach
    public void tearDown() {
        devsManageJobs(false);
    }

    private Team devsManageJobs(boolean canManage) {
        Team team = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7")).get();
        team.setManageJob(canManage);
        return teamRepository.save(team);
    }

    private String workspaceLockPayload(boolean isLocked) {
        return String.format("{\n" +
                "  \"data\": {\n" +
                "    \"type\": \"workspace\",\n" +
                "    \"id\": \"%s\",\n" +
                "    \"attributes\": {\n" +
                "      \"locked\": \"%b\"\n" +
                "    }\n" +
                "  }\n" +
                "}", workspace.getId(), isLocked);
    }

    private String jobDefinition(String templateRef) {
        return String.format("{\n" +
                "  \"data\": {\n" +
                "    \"type\": \"job\",\n" +
                "    \"attributes\": {\n" +
                "      \"templateReference\": \"%s\"\n" +
                "    },\n" +
                "    \"relationships\":{\n" +
                "        \"workspace\":{\n" +
                "            \"data\":{\n" +
                "                \"type\": \"workspace\",\n" +
                "                \"id\": \"%s\"\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "  }\n" +
                "}", templateRef, workspace.getId().toString());
    }

    private String statusChangePayload(String jobId, JobStatus status) {
        return String.format(
                "{\"data\": {\"type\": \"job\", \"id\": \"%s\", \"attributes\": {\"status\": \"%s\"}}}",
                jobId,
                status.toString()
        );
    }

    private String createJob(String jobDefinition) {
        String jobId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(jobDefinition)
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job")
                .then()
                .extract()
                .path("data.id");
        return jobId;
    }

    private Template createTemplate(String tcl) {
        Template template = new Template();
        template.setName("ze-template");
        template.setTcl(Base64.encodeBytes(tcl.getBytes()));
        template.setOrganization(organization);
        return templateRepository.save(template);
    }

    @Test
    void createJobAsOrgMember() {
        devsManageJobs(true);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(jobDefinition("2db36f7c-f549-4341-a789-315d47eb061d"))
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/")
                .then()
                .assertThat()
                .body("data.attributes.templateReference", IsEqual.equalTo("2db36f7c-f549-4341-a789-315d47eb061d"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path("data.id");
   }

    @Test
    void createJobLockedWorkspace() {
        devsManageJobs(true);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(workspaceLockPayload(true))
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspace.getId())
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        createJob(jobDefinition("2db36f7c-f549-4341-a789-315d47eb061d"));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(workspaceLockPayload(false))
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspace.getId())
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    public void jobCancellationRequiresManageJobPermission() {
        devsManageJobs(true);
        String jobId = createJob(jobDefinition("2db36f7c-f549-4341-a789-315d47eb061d"));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(statusChangePayload(jobId, JobStatus.cancelled))
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/" + jobId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        devsManageJobs(false);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(statusChangePayload(jobId, JobStatus.cancelled))
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/" + jobId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    public void appointedTeamCanApproveJobWithoutManageJobPermission() throws InterruptedException {
        Template template = createTemplate("flow:\n- name: Approve\n  type: approval\n  team: TERRAKUBE_DEVELOPERS\n");

        devsManageJobs(true);
        String jobId = createJob(jobDefinition(template.getId().toString()));
        devsManageJobs(false);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(statusChangePayload(jobId, JobStatus.approved))
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/" + jobId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    public void onlyAppointedTeamCanApprove() {
        Template template = createTemplate("flow:\n- name: Approve\n  type: approval\n  team: OTHER_TEAM\n");

        devsManageJobs(true);
        String jobId = createJob(jobDefinition(template.getId().toString()));
        devsManageJobs(false);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(statusChangePayload(jobId, JobStatus.approved))
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/" + jobId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    public void anyoneCanApproveFlowsWithoutTeam() throws InterruptedException {
        Template template = createTemplate("flow:\n- name: Approve\n  type: approval\n");

        devsManageJobs(true);
        String jobId = createJob(jobDefinition(template.getId().toString()));
        devsManageJobs(false);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(statusChangePayload(jobId, JobStatus.approved))
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/" + jobId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }
}
