package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

public class LocalStorageTests extends ServerApplicationTests {

    private static final String OUTPUT_DIRECTORY = "%s/.terraform-spring-boot/local/output/%s/%s/%s.tfoutput";
    private static final String STATE_DIRECTORY = "%s/.terraform-spring-boot/local/state/%s/%s/%s/%s/terraformLibrary.tfPlan";
    private static final String STATE_DIRECTORY_JSON = "%s/.terraform-spring-boot/local/state/%s/%s/state/%s.json";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testLocalStorageJSON() throws IOException {
        FileUtils.writeStringToFile(
                new File(
                        String.format(STATE_DIRECTORY_JSON, FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc", "1")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"team\",\n" +
                        "    \"id\": \"58529721-425e-44d7-8b0d-1d515043c2f7\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"manageState\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team/58529721-425e-44d7-8b0d-1d515043c2f7")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/state/1.json")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

    }

    @Test
    void testLocalStorageJSONWithoutManageStatePermission() throws IOException {
        FileUtils.writeStringToFile(
                new File(
                        String.format(STATE_DIRECTORY_JSON, FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc", "1")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"team\",\n" +
                        "    \"id\": \"58529721-425e-44d7-8b0d-1d515043c2f7\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"manageState\": false,\n" +
                        "      \"role\": \"custom\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team/58529721-425e-44d7-8b0d-1d515043c2f7")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/state/1.json")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

    }

    @Test
    void testLocalStorageBinaryState() throws IOException {
        FileUtils.writeStringToFile(
                new File(
                        String.format(STATE_DIRECTORY, FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc", "2", "2")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/jobId/2/step/2/terraform.tfstate")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

    }

    @Test
    void testLocalStorageBinaryStateWithoutManageStatePermission() throws IOException {
        FileUtils.writeStringToFile(
                new File(
                        String.format(STATE_DIRECTORY, FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc", "2", "2")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("OTHER_TEAM"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/jobId/2/step/2/terraform.tfstate")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void testLocalStorageBinaryStateWithInternalToken() throws IOException {
        FileUtils.writeStringToFile(
                new File(
                        String.format(STATE_DIRECTORY, FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc", "2", "2")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generateSystemToken())
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/jobId/2/step/2/terraform.tfstate")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void testLocalStorageOutputJob() throws IOException {
        Organization org = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(100);
        step.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        step = stepRepository.save(step);

        FileUtils.writeStringToFile(
                new File(
                        String.format(OUTPUT_DIRECTORY, FileUtils.getUserDirectoryPath(), org.getId().toString(), String.valueOf(job.getId()), step.getId().toString())),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfoutput/v1/organization/" + org.getId() + "/job/" + job.getId() + "/step/" + step.getId())
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

    }

    @Test
    void streamEndpointConnectsAndClosesCleanlyForValidStep() {
        Organization org = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(101);
        step.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        step = stepRepository.save(step);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfoutput/v1/organization/" + org.getId() + "/job/" + job.getId() + "/step/" + step.getId() + "/stream")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void streamEndpointAcceptsLastEventIdHeader() {
        Organization org = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(102);
        step.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        step = stepRepository.save(step);

        given()
                .headers(
                        "Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Last-Event-ID", "100-0")
                .when()
                .get("/tfoutput/v1/organization/" + org.getId() + "/job/" + job.getId() + "/step/" + step.getId() + "/stream")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void testOutputDeniedForUnauthorizedUser() throws IOException {
        Organization org = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(103);
        step.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        step = stepRepository.save(step);

        FileUtils.writeStringToFile(
                new File(
                        String.format(OUTPUT_DIRECTORY, FileUtils.getUserDirectoryPath(), org.getId().toString(), String.valueOf(job.getId()), step.getId().toString())),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("OTHER_TEAM"))
                .when()
                .get("/tfoutput/v1/organization/" + org.getId() + "/job/" + job.getId() + "/step/" + step.getId())
                .then()
                .assertThat()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void testOutputNotFoundForMismatchedOrganization() throws IOException {
        Organization org = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(104);
        step.setStatus(io.terrakube.api.rs.job.JobStatus.completed);
        step = stepRepository.save(step);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfoutput/v1/organization/" + UUID.randomUUID() + "/job/" + job.getId() + "/step/" + step.getId())
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

}

