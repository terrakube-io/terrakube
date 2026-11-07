package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.module.ModuleRefreshPollerScheduler;
import io.terrakube.api.plugin.scheduler.module.ModuleRefreshTransactions;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.rs.module.Module;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ModuleTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final JobKey POLLER = new JobKey(ModuleRefreshPollerScheduler.PREFIX_MODULE_REFRESH_POLLER);
    private static final Date LEASE = Date.from(Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(1, ChronoUnit.DAYS));

    @Autowired
    ModuleRepository moduleRepository;

    @Autowired
    ModuleRefreshTransactions moduleRefreshTransactions;

    // The real poller would claim the modules created here and change next_refresh under the assertions.
    @BeforeAll
    void pausePoller() throws SchedulerException {
        scheduler.pauseJob(POLLER);
    }

    @AfterAll
    void resumePoller() throws SchedulerException {
        scheduler.resumeJob(POLLER);
    }

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** A module of the demo organization that the poller has claimed with LEASE. */
    private UUID claimedModule() {
        Module module = new Module();
        module.setName("claimed-" + UUID.randomUUID().toString().substring(0, 8));
        module.setProvider("aws");
        module.setSource("https://git.example.test/acme/network.git");
        module.setOrganization(organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow());
        UUID id = moduleRepository.save(module).getId();
        assertThat(moduleRefreshTransactions.claim(id, LEASE, new Date())).isTrue();
        return id;
    }

    private int patchModule(UUID id, String attributes) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\": {\"type\": \"module\", \"id\": \"" + id + "\", \"attributes\": " + attributes + "}}")
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/module/" + id)
                .then()
                .log().ifValidationFails()
                .extract().statusCode();
    }

    @Test
    void searchModuleAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/4e92ff1e-9937-400f-848d-f0ea367927bf")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("kubernetes-engine"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createModuleAsOrgMember() throws SchedulerException {
        String moduleId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"terrakube-storage\",\n" +
                        "      \"description\": \"Terrakube Storage Module\",\n" +
                        "      \"provider\": \"azurerm\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("terrakube-storage"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // No job per module any more: a new module is due at once (NULL next_refresh) for the poller.
        assertThat(scheduler.getJobDetail(new JobKey("TerrakubeV2_ModuleRefresh_" + moduleId))).isNull();
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/" + ORGANIZATION_ID + "/module/" + moduleId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body("data.attributes.nextRefresh", Matchers.nullValue());
    }

    @Test
    void patchOfSourceMakesAClaimedModuleDue() {
        UUID id = claimedModule();

        assertThat(patchModule(id, "{\"source\": \"https://git.example.test/acme/network-v2.git\"}"))
                .isEqualTo(HttpStatus.NO_CONTENT.value());

        assertThat(moduleRepository.findById(id).orElseThrow().getNextRefresh()).isNull();
    }

    @Test
    void patchOfDescriptionKeepsTheLease() {
        UUID id = claimedModule();

        assertThat(patchModule(id, "{\"description\": \"Network module\"}")).isEqualTo(HttpStatus.NO_CONTENT.value());

        Module stored = moduleRepository.findById(id).orElseThrow();
        assertThat(stored.getDescription()).isEqualTo("Network module");
        assertThat(stored.getNextRefresh().getTime()).isEqualTo(LEASE.getTime());
    }

    @Test
    void clientCannotSetRefreshState() {
        UUID id = claimedModule();

        assertThat(patchModule(id, "{\"lastRefreshError\": \"forged\"}")).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(patchModule(id, "{\"nextRefresh\": null}")).isEqualTo(HttpStatus.FORBIDDEN.value());

        assertThat(moduleRepository.findById(id).orElseThrow().getLastRefreshError()).isNull();
    }

    @Test
    void createDuplicateModuleIsRejectedWithConflict() {
        String body = "{\n" +
                "  \"data\": {\n" +
                "    \"type\": \"module\",\n" +
                "    \"attributes\": {\n" +
                "      \"name\": \"terrakube-storage-dup\",\n" +
                "      \"description\": \"Terrakube Storage Module\",\n" +
                "      \"provider\": \"azurerm\",\n" +
                "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                "    }\n" +
                "  }\n" +
                "}";

        String moduleId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(body)
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Wait for the first module to be durably committed and queryable before attempting the
        // second create - this models the real reported scenario (two sequential, spaced-out create
        // attempts), not a genuinely concurrent race.
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/" + moduleId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(body)
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CONFLICT.value())
                .body("errors[0].detail", org.hamcrest.Matchers.containsString("already exists"));
    }

    @Test
    void createModuleWithInvalidSystemIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"terrakube-storage-ecs\",\n" +
                        "      \"description\": \"Terrakube Storage Module\",\n" +
                        "      \"provider\": \"aws-ecs\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void updateModuleWithInvalidSystemIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"id\": \"4e92ff1e-9937-400f-848d-f0ea367927bf\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"provider\": \"aws-ecs\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/4e92ff1e-9937-400f-848d-f0ea367927bf")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void searchModuleAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/4e92ff1e-9937-400f-848d-f0ea367927bf")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createModuleAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"terrakube-storage-fake\",\n" +
                        "      \"description\": \"Terrakube Storage Module\",\n" +
                        "      \"provider\": \"azurerm\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }
}
