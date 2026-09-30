package io.terrakube.api;

import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.VersionStatus;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Version;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

class RegistryVersionDeprecationTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final String MODULE_ID = "4e92ff1e-9937-400f-848d-f0ea367927bf";
    private static final String MANAGER_TEAM = "TERRAKUBE_DEVELOPERS";

    @Autowired
    ModuleRepository moduleRepository;
    @Autowired
    ModuleVersionRepository moduleVersionRepository;
    @Autowired
    ProviderRepository providerRepository;
    @Autowired
    ProviderVersionRepository providerVersionRepository;

    private ModuleVersion moduleVersion;
    private String originalLatestVersion;
    private Provider provider;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Module module = moduleRepository.findById(UUID.fromString(MODULE_ID)).orElseThrow();
        originalLatestVersion = module.getLatestVersion();
        moduleVersion = new ModuleVersion();
        moduleVersion.setModule(module);
        moduleVersion.setVersion("99.0.0");
        moduleVersion.setCommit("0000000");
        moduleVersion = moduleVersionRepository.save(moduleVersion);

        provider = new Provider();
        provider.setName("deprecation-test");
        provider.setOrganization(organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow());
        provider = providerRepository.save(provider);
        providerVersionRepository.save(providerVersion("1.0.0", VersionStatus.active, null));
        providerVersionRepository.save(providerVersion("1.1.0", VersionStatus.deprecated, "Removal on 2026-12-31"));
        providerVersionRepository.save(providerVersion("1.2.0", VersionStatus.removed, "Broken release, use 1.3.0"));
    }

    @AfterEach
    void cleanup() {
        moduleVersionRepository.findById(moduleVersion.getId()).ifPresent(moduleVersionRepository::delete);
        Module module = moduleRepository.findById(UUID.fromString(MODULE_ID)).orElseThrow();
        module.setLatestVersion(originalLatestVersion);
        moduleRepository.save(module);
        providerVersionRepository.deleteAll(providerVersionRepository.findAllByProviderId(provider.getId()));
        providerRepository.delete(provider);
    }

    private Version providerVersion(String number, VersionStatus status, String message) {
        Version version = new Version();
        version.setProvider(provider);
        version.setVersionNumber(number);
        version.setProtocols("5.0");
        version.setStatus(status);
        version.setDeprecationMessage(message);
        return version;
    }

    private int patchModuleVersion(String attributes) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(MANAGER_TEAM), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"module_version","id":"%s","attributes":%s}}
                        """.formatted(moduleVersion.getId(), attributes))
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/module/" + MODULE_ID + "/version/" + moduleVersion.getId())
                .then()
                .extract().statusCode();
    }

    private ModuleVersion savedModuleVersion() {
        return moduleVersionRepository.findById(moduleVersion.getId()).orElseThrow();
    }

    private String latestVersion() {
        return moduleRepository.findById(UUID.fromString(MODULE_ID)).orElseThrow().getLatestVersion();
    }

    @Test
    void newVersionsAreActive() {
        assertEquals(VersionStatus.active, savedModuleVersion().getStatus());
    }

    @Test
    void managerCanDeprecateModuleVersion() {
        assertEquals(HttpStatus.NO_CONTENT.value(), patchModuleVersion("""
                {"status":"deprecated","deprecationMessage":"Use 100.x"}"""));

        assertEquals(VersionStatus.deprecated, savedModuleVersion().getStatus());
        assertEquals("Use 100.x", savedModuleVersion().getDeprecationMessage());
    }

    // The message can reach a terminal: C0/C1 controls start escape sequences and bidi overrides reorder text.
    @Test
    void controlAndFormatCharactersAreStrippedFromTheMessage() {
        assertEquals(HttpStatus.NO_CONTENT.value(), patchModuleVersion("""
                {"status":"deprecated","deprecationMessage":"\\u001b[31mUse\\u009b2J 100.x\\u202e\\n"}"""));

        assertEquals("[31mUse 2J 100.x", savedModuleVersion().getDeprecationMessage());
    }

    @Test
    void messageLongerThan1024CharactersIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST.value(), patchModuleVersion("""
                {"status":"deprecated","deprecationMessage":"%s"}""".formatted("x".repeat(1025))));

        assertEquals(VersionStatus.active, savedModuleVersion().getStatus());
    }

    @Test
    void removingTheNewestVersionMovesLatestVersionWithoutARefresh() {
        // Deprecated versions are still served, so they still count as the latest version.
        assertEquals(HttpStatus.NO_CONTENT.value(), patchModuleVersion("""
                {"status":"deprecated"}"""));
        assertEquals("99.0.0", latestVersion());

        assertEquals(HttpStatus.NO_CONTENT.value(), patchModuleVersion("""
                {"status":"removed"}"""));
        assertNotEquals("99.0.0", latestVersion());

        assertEquals(HttpStatus.NO_CONTENT.value(), patchModuleVersion("""
                {"status":"active"}"""));
        assertEquals("99.0.0", latestVersion());
    }

    // The registry relies on these exact filters: it lists served versions with status!=removed and
    // finds deprecated and removed ones with status!=active.
    @Test
    void graphQlFiltersVersionsByStatus() {
        String query = """
                { "query": "{ organization(ids: [\\"%s\\"]) { edges { node { provider(filter: \\"name==deprecation-test\\") { edges { node { served: version(filter: \\"status!=removed\\") { edges { node { versionNumber } } } flagged: version(filter: \\"status!=active\\") { edges { node { versionNumber } } } } } } } } } }" }
                """.formatted(ORGANIZATION_ID);

        given()
                .headers("Authorization", "Bearer " + generatePAT(MANAGER_TEAM), "Content-Type", "application/json")
                .body(query)
                .when()
                .post("/graphql/api/v1")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.organization.edges[0].node.provider.edges[0].node.served.edges.node.versionNumber",
                        containsInAnyOrder("1.0.0", "1.1.0"))
                .body("data.organization.edges[0].node.provider.edges[0].node.flagged.edges.node.versionNumber",
                        containsInAnyOrder("1.1.0", "1.2.0"));
    }
}
