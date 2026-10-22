package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.team.Team;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import io.terrakube.api.rs.job.Job;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = "spring.quartz.auto-startup=false")
class JobVariablesApiTests extends ServerApplicationTests {
    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String ROUTE = "/api/v1/organization/" + ORG + "/job";
    @MockitoBean ScheduleJobService scheduleJobService;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.terrakube.api.plugin.execution.JobVariablesService variablesService;

    @Test
    void acceptsCreateOnlyVariablesEncryptsBeforeCommitAndOmitsFromReads() {
        Workspace workspace = new Workspace();
        workspace.setName("job-variable-test");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        workspace = workspaceRepository.save(workspace);
        Team team = new Team();
        team.setName("JOB_VARIABLE_TEST");
        team.setRole("admin");
        team.setOrganization(workspace.getOrganization());
        teamRepository.save(team);
        String token = generatePAT("JOB_VARIABLE_TEST");
        Map<String, Object> body = Map.of("data", Map.of("type", "job",
                "attributes", Map.of("variables", Map.of("key", "private-test-value\nsecond-line", "empty", "")),
                "relationships", Map.of("workspace", Map.of("data", Map.of("type", "workspace", "id", workspace.getId().toString())))));
        var response = given().auth().oauth2(token).contentType("application/vnd.api+json")
                .body(body).post(ROUTE);
        assertEquals(201, response.statusCode(), response.asString());
        String id = response.path("data.id");
        assertFalse(response.asString().contains("private-test-value"));
        Job stored = jobRepository.findById(Integer.parseInt(id)).orElseThrow();
        String encrypted = jdbc.queryForObject("select encrypted_variables from job where id = ?", String.class, Integer.parseInt(id));
        assertNotNull(encrypted);
        assertFalse(encrypted.contains("private-test-value"));
        assertNull(stored.getVariables());
        assertEquals("private-test-value\nsecond-line", variablesService.decrypt(stored.getEncryptedVariables()).get("key"));
        var read = given().auth().oauth2(token).get(ROUTE + "/" + id);
        assertEquals(200, read.statusCode());
        assertFalse(read.asString().contains("private-test-value"));
        assertFalse(read.asString().contains("encryptedVariables"));
        assertFalse(read.jsonPath().getMap("data.attributes").containsKey("variables"));
        assertEquals(java.util.List.of("empty", "key"), read.jsonPath().getList("data.attributes.variableNames"));
        assertFalse(read.asString().contains("encryptedVariables"));
        var graphql = given().auth().oauth2(token).contentType("application/json")
                .body(Map.of("query", "{ organization(ids: [\"" + ORG + "\"]) { edges { node { job(ids: [\"" + id + "\"]) { edges { node { id variables } } } } } } }"))
                .post("/graphql/api/v1");
        assertFalse(graphql.asString().contains("private-test-value"));
        jdbc.update("update job set encrypted_variables = ? where id = ?", "not-decryptable", Integer.parseInt(id));
        assertEquals(200, given().auth().oauth2(token).get(ROUTE + "/" + id).statusCode());
        jdbc.update("update job set encrypted_variables = ? where id = ?", encrypted, Integer.parseInt(id));
        var update = given().auth().oauth2(token).contentType("application/vnd.api+json")
                .body(Map.of("data", Map.of("type", "job", "id", id,
                        "attributes", Map.of("variables", Map.of("key", "replacement")))))
                .patch(ROUTE + "/" + id);
        assertTrue(update.statusCode() >= 400, update.asString());
        assertEquals(stored.getEncryptedVariables(), jobRepository.findById(Integer.parseInt(id)).orElseThrow().getEncryptedVariables());
        var invalid = Map.of("data", Map.of("type", "job",
                "attributes", Map.of("variables", Map.of("bad name", "private-marker")),
                "relationships", Map.of("workspace", Map.of("data", Map.of("type", "workspace", "id", workspace.getId().toString())))));
        long before = jobRepository.count();
        var rejected = given().auth().oauth2(token).contentType("application/vnd.api+json").body(invalid).post(ROUTE);
        assertEquals(400, rejected.statusCode(), rejected.asString());
        assertFalse(rejected.asString().contains("private-marker"));
        assertEquals(before, jobRepository.count());
    }

    @Test
    void capabilityRequiresAuthentication() {
        assertEquals(401, given().get("/execution/v1/capabilities").statusCode());
        var response = given().auth().oauth2(generatePAT("TERRAKUBE_ADMIN")).get("/execution/v1/capabilities");
        assertEquals(200, response.statusCode());
        assertEquals(Boolean.TRUE, response.path("jobVariables"));
    }
}
