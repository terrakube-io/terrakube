package io.terrakube.api;

import io.terrakube.api.rs.workspace.Workspace;
import org.apache.commons.io.FileUtils;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
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
    void jobTokenReadsOnlyStateSharedWithItsWorkspace() throws IOException {
        String orgId = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
        String sharedWorkspaceId = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
        String jobWorkspaceId = "24480d33-2649-4c34-aabd-cbc988eb6265";
        FileUtils.writeStringToFile(
                new File(String.format(STATE_DIRECTORY_JSON, FileUtils.getUserDirectoryPath(), orgId, sharedWorkspaceId, "1")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );
        String stateUrl = "/tfstate/v1/organization/" + orgId + "/workspace/" + sharedWorkspaceId + "/state/1.json";
        String jobToken = generateSystemToken(Map.of("workspaceId", jobWorkspaceId));

        Workspace workspace = workspaceRepository.findById(UUID.fromString(sharedWorkspaceId)).get();
        workspace.setGlobalRemoteState(false);
        workspace.setSharedIds("");
        workspaceRepository.save(workspace);
        try {
            given().headers("Authorization", "Bearer " + jobToken)
                    .when().get(stateUrl)
                    .then().statusCode(HttpStatus.FORBIDDEN.value());

            workspace.setSharedIds(jobWorkspaceId);
            workspaceRepository.save(workspace);
            given().headers("Authorization", "Bearer " + jobToken)
                    .when().get(stateUrl)
                    .then().statusCode(HttpStatus.OK.value());

            given().headers("Authorization", "Bearer " + jobToken)
                    .when().put("/tfstate/v1/organization/" + orgId + "/workspace/" + sharedWorkspaceId + "/rollback/1.json")
                    .then().statusCode(HttpStatus.FORBIDDEN.value());
        } finally {
            workspace.setGlobalRemoteState(true);
            workspace.setSharedIds(null);
            workspaceRepository.save(workspace);
        }
    }

    @Test
    void jobTokenIsNotTreatedAsInstanceOwner() {
        given().headers("Authorization", "Bearer " + generateSystemToken(Map.of("workspaceId", "24480d33-2649-4c34-aabd-cbc988eb6265")))
                .when().get("/api/v1/organization")
                .then().statusCode(HttpStatus.OK.value())
                .body("data.size()", IsEqual.equalTo(0));

        given().headers("Authorization", "Bearer " + generateSystemToken())
                .when().get("/api/v1/organization")
                .then().statusCode(HttpStatus.OK.value())
                .body("data.size()", Matchers.greaterThan(0));
    }

    @Test
    void testLocalStorageBinaryState() throws IOException {
        FileUtils.writeStringToFile(
                new File(
                        String.format(STATE_DIRECTORY, FileUtils.getUserDirectoryPath(), "2", "2", "2", "2")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfstate/v1/organization/2/workspace/2/jobId/2/step/2/terraform.tfstate")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

    }

    @Test
    void testLocalStorageOutputJob() throws IOException {
        FileUtils.writeStringToFile(
                new File(
                        String.format(OUTPUT_DIRECTORY, FileUtils.getUserDirectoryPath(), "3", "3", "3")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfoutput/v1/organization/3/job/3/step/3")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

    }

    @Test
    void streamEndpointConnectsAndClosesCleanlyForUnknownStep() {
        // Unknown job/step -> JobStatusCache treats a missing job as terminal, so the broadcaster
        // completes the emitter immediately: the SSE connection opens and closes cleanly (200,
        // empty stream) instead of hanging or 500-ing.
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfoutput/v1/organization/3/job/3/step/11111111-1111-1111-1111-111111111111/stream")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void streamEndpointAcceptsLastEventIdHeader() {
        // Same unknown-step path as streamEndpointConnectsAndClosesCleanlyForUnknownStep, confirming
        // the endpoint still accepts a Last-Event-ID header without erroring on the header parsing.
        given()
                .headers(
                        "Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Last-Event-ID", "100-0")
                .when()
                .get("/tfoutput/v1/organization/3/job/3/step/11111111-1111-1111-1111-111111111111/stream")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());
    }

}
