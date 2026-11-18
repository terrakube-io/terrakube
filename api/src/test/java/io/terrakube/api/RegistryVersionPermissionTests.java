package io.terrakube.api;

import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.repository.ProviderImplementationRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Version;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

// Module and provider versions used to have no permissions of their own, so any team that could read
// a module or provider could create, change or delete its versions.
class RegistryVersionPermissionTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final String MODULE_ID = "4e92ff1e-9937-400f-848d-f0ea367927bf";
    private static final String MANAGER_TEAM = "TERRAKUBE_DEVELOPERS";
    private static final String VIEW_ONLY_TEAM = "REGISTRY_VIEW_ONLY";

    @Autowired
    ModuleRepository moduleRepository;
    @Autowired
    ModuleVersionRepository moduleVersionRepository;
    @Autowired
    ProviderRepository providerRepository;
    @Autowired
    ProviderVersionRepository providerVersionRepository;
    @Autowired
    ProviderImplementationRepository providerImplementationRepository;

    private ModuleVersion moduleVersion;
    private Provider provider;
    private Version providerVersion;
    private Team viewOnlyTeam;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        moduleVersion = new ModuleVersion();
        moduleVersion.setModule(moduleRepository.findById(UUID.fromString(MODULE_ID)).orElseThrow());
        moduleVersion.setVersion("9.9.9");
        moduleVersion.setCommit("0000000");
        moduleVersion = moduleVersionRepository.save(moduleVersion);

        provider = new Provider();
        provider.setName("permission-test");
        provider.setOrganization(organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow());
        provider = providerRepository.save(provider);
        providerVersion = new Version();
        providerVersion.setProvider(provider);
        providerVersion.setVersionNumber("1.0.0");
        providerVersion.setProtocols("5.0");
        providerVersion = providerVersionRepository.save(providerVersion);

        viewOnlyTeam = new Team();
        viewOnlyTeam.setName(VIEW_ONLY_TEAM);
        viewOnlyTeam.setOrganization(provider.getOrganization());
        viewOnlyTeam = teamRepository.save(viewOnlyTeam);
    }

    @AfterEach
    void cleanup() {
        moduleVersionRepository.findAllByModuleId(UUID.fromString(MODULE_ID)).stream()
                .filter(version -> version.getVersion().startsWith("9.9."))
                .forEach(moduleVersionRepository::delete);
        providerVersionRepository.findAllByProviderId(provider.getId()).forEach(version -> {
            providerImplementationRepository.deleteAll(providerImplementationRepository.findAllByVersionId(version.getId()));
            providerVersionRepository.delete(version);
        });
        providerRepository.delete(provider);
        teamRepository.delete(viewOnlyTeam);
    }

    private String moduleVersionsUrl() {
        return "/api/v1/organization/" + ORGANIZATION_ID + "/module/" + MODULE_ID + "/version";
    }

    private String providerVersionsUrl() {
        return "/api/v1/organization/" + ORGANIZATION_ID + "/provider/" + provider.getId() + "/version";
    }

    private int patchModuleVersion(String group) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"module_version","id":"%s","attributes":{"gitTag":"v9.9.9-changed"}}}
                        """.formatted(moduleVersion.getId()))
                .when()
                .patch(moduleVersionsUrl() + "/" + moduleVersion.getId())
                .then()
                .extract().statusCode();
    }

    @Test
    void moduleManagerCanUpdateModuleVersion() {
        assertEquals(HttpStatus.NO_CONTENT.value(), patchModuleVersion(MANAGER_TEAM));
        assertEquals("v9.9.9-changed", moduleVersionRepository.findById(moduleVersion.getId()).orElseThrow().getGitTag());
    }

    @Test
    void teamWithoutManageModuleCannotUpdateModuleVersion() {
        assertEquals(HttpStatus.FORBIDDEN.value(), patchModuleVersion(VIEW_ONLY_TEAM));
        assertEquals(null, moduleVersionRepository.findById(moduleVersion.getId()).orElseThrow().getGitTag());
    }

    @Test
    void teamWithoutManageModuleCannotDeleteModuleVersion() {
        given()
                .headers("Authorization", "Bearer " + generatePAT(VIEW_ONLY_TEAM))
                .when()
                .delete(moduleVersionsUrl() + "/" + moduleVersion.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
        assertTrue(moduleVersionRepository.findById(moduleVersion.getId()).isPresent());
    }

    private int createModuleVersion(String group) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"module_version","attributes":{"version":"9.9.10","commit":"0000000"}}}
                        """)
                .when()
                .post(moduleVersionsUrl())
                .then()
                .extract().statusCode();
    }

    @Test
    void moduleManagerCanCreateModuleVersion() {
        assertEquals(HttpStatus.CREATED.value(), createModuleVersion(MANAGER_TEAM));
    }

    @Test
    void teamWithoutManageModuleCannotCreateModuleVersion() {
        assertEquals(HttpStatus.FORBIDDEN.value(), createModuleVersion(VIEW_ONLY_TEAM));
    }

    private int createProviderVersion(String group) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"version","attributes":{"versionNumber":"2.0.0","protocols":"5.0"}}}
                        """)
                .when()
                .post(providerVersionsUrl())
                .then()
                .extract().statusCode();
    }

    @Test
    void providerManagerCanCreateProviderVersion() {
        assertEquals(HttpStatus.CREATED.value(), createProviderVersion(MANAGER_TEAM));
    }

    @Test
    void teamWithoutManageProviderCannotCreateProviderVersion() {
        assertEquals(HttpStatus.FORBIDDEN.value(), createProviderVersion(VIEW_ONLY_TEAM));
    }

    @Test
    void teamWithoutManageProviderCannotDeleteProviderVersion() {
        given()
                .headers("Authorization", "Bearer " + generatePAT(VIEW_ONLY_TEAM))
                .when()
                .delete(providerVersionsUrl() + "/" + providerVersion.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
        assertTrue(providerVersionRepository.findById(providerVersion.getId()).isPresent());
    }

    // Adding an implementation updates the version's implementation collection, so it goes through the
    // version's update permission as well.
    private int createImplementation(String group) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"implementation","attributes":{"os":"linux","arch":"amd64","filename":"p.zip","downloadUrl":"https://example.com/p.zip","shasumsUrl":"https://example.com/SHA256SUMS","shasumsSignatureUrl":"https://example.com/SHA256SUMS.sig","shasum":"abc","keyId":"KEY","asciiArmor":"ARMOR","trustSignature":"","source":"test","sourceUrl":"https://example.com"}}}
                        """)
                .when()
                .post(providerVersionsUrl() + "/" + providerVersion.getId() + "/implementation")
                .then()
                .extract().statusCode();
    }

    @Test
    void providerManagerCanAddImplementations() {
        assertEquals(HttpStatus.CREATED.value(), createImplementation(MANAGER_TEAM));
    }

    @Test
    void teamWithoutManageProviderCannotAddImplementations() {
        assertEquals(HttpStatus.FORBIDDEN.value(), createImplementation(VIEW_ONLY_TEAM));
    }

    @Test
    void registryServiceCanReadJobAndStepStatusWithoutOutputOrTerraformPlan() {
        Workspace workspace = new Workspace();
        workspace.setName("registry-perm-ws-" + UUID.randomUUID());
        workspace.setSource("https://github.com/terrakube-io/terrakube.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.13.1");
        workspace.setOrganization(provider.getOrganization());
        workspace = workspaceRepository.saveAndFlush(workspace);

        Job job = new Job();
        job.setOrganization(provider.getOrganization());
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.running);
        job.setOutput("secret-job-output");
        job.setTerraformPlan("secret-terraform-plan");
        job = jobRepository.saveAndFlush(job);

        Step step = new Step();
        step.setJob(job);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.running);
        step.setStepNumber(100);
        step.setOutput("secret-step-output");
        step = stepRepository.saveAndFlush(step);

        try {
            String registryToken = generateRegistryToken();

            var jsonApiResponse = given()
                    .headers("Authorization", "Bearer " + registryToken, "Accept", "application/vnd.api+json")
                    .when()
                    .get("/api/v1/organization/" + ORGANIZATION_ID + "/job/" + job.getId() + "?include=step")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .extract()
                    .response();

            assertEquals("running", jsonApiResponse.jsonPath().getString("data.attributes.status"));
            assertEquals(workspace.getId().toString(), jsonApiResponse.jsonPath().getString("data.relationships.workspace.data.id"));
            assertNull(jsonApiResponse.jsonPath().get("data.attributes.output"));
            assertNull(jsonApiResponse.jsonPath().get("data.attributes.terraformPlan"));
            assertEquals("running", jsonApiResponse.jsonPath().getString("included[0].attributes.status"));
            assertNull(jsonApiResponse.jsonPath().get("included[0].attributes.output"));

            String graphqlQuery = "{\"query\":\"{ organization(ids: [\\\"" + ORGANIZATION_ID
                    + "\\\"]) { edges { node { job(ids: [\\\"" + job.getId()
                    + "\\\"]) { edges { node { id status output terraformPlan step { edges { node { id status output } } } } } } } } } }\"}";

            var graphqlResponse = given()
                    .headers(
                            "Authorization", "Bearer " + registryToken,
                            "Content-Type", "application/json",
                            "Accept", "application/json"
                    )
                    .body(graphqlQuery)
                    .when()
                    .post("/graphql/api/v1")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .extract()
                    .response();

            assertNotNull(graphqlResponse.jsonPath().getList("errors"),
                    "Expected Elide GraphQL errors when REGISTRY_SERVICE reads restricted output/terraformPlan fields: "
                            + graphqlResponse.asString());
            assertTrue(graphqlResponse.asString().contains("ReadPermission")
                            || Matchers.not(Matchers.empty()).matches(graphqlResponse.jsonPath().getList("errors")),
                    "Expected non-empty GraphQL errors for restricted fields");
        } finally {
            stepRepository.delete(step);
            jobRepository.delete(job);
            workspaceRepository.delete(workspace);
        }
    }
}
