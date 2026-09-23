package io.terrakube.api;

import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.repository.ProviderImplementationRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Version;
import io.terrakube.api.rs.team.Team;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class RegistryVersionDeprecationTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final String MODULE_ID = "4e92ff1e-9937-400f-848d-f0ea367927bf";
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
    private Team viewOnlyTeam;
    private Version firstProviderVersion;

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
        provider.setName("deprecation-test");
        provider.setOrganization(organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow());
        provider = providerRepository.save(provider);
        firstProviderVersion = providerVersionRepository.save(providerVersion("1.0.0", false, false, null));
        providerVersionRepository.save(providerVersion("1.1.0", true, false, "Removal on 2026-12-31"));
        providerVersionRepository.save(providerVersion("1.2.0", false, true, "Broken release, use 1.3.0"));

        viewOnlyTeam = new Team();
        viewOnlyTeam.setName(VIEW_ONLY_TEAM);
        viewOnlyTeam.setOrganization(provider.getOrganization());
        viewOnlyTeam = teamRepository.save(viewOnlyTeam);
    }

    @AfterEach
    void cleanup() {
        moduleVersionRepository.delete(moduleVersion);
        providerImplementationRepository.deleteAll(providerImplementationRepository.findAllByVersionId(firstProviderVersion.getId()));
        providerVersionRepository.deleteAll(providerVersionRepository.findAllByProviderId(provider.getId()));
        providerRepository.delete(provider);
        teamRepository.delete(viewOnlyTeam);
    }

    private Version providerVersion(String number, boolean deprecated, boolean removed, String message) {
        Version version = new Version();
        version.setProvider(provider);
        version.setVersionNumber(number);
        version.setProtocols("5.0");
        version.setDeprecated(deprecated);
        version.setRemoved(removed);
        version.setDeprecationMessage(message);
        return version;
    }

    private int patchModuleVersion(String group) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"module_version","id":"%s","attributes":{"deprecated":true,"deprecationMessage":"Use 10.x"}}}
                        """.formatted(moduleVersion.getId()))
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/module/" + MODULE_ID + "/version/" + moduleVersion.getId())
                .then()
                .extract().statusCode();
    }

    @Test
    void moduleManagerCanDeprecateModuleVersion() {
        assertEquals(HttpStatus.NO_CONTENT.value(), patchModuleVersion("TERRAKUBE_DEVELOPERS"));

        ModuleVersion saved = moduleVersionRepository.findById(moduleVersion.getId()).orElseThrow();
        assertTrue(saved.isDeprecated());
        assertEquals("Use 10.x", saved.getDeprecationMessage());
    }

    @Test
    void teamWithoutManageModuleCannotDeprecateModuleVersion() {
        assertEquals(HttpStatus.FORBIDDEN.value(), patchModuleVersion(VIEW_ONLY_TEAM));
        assertFalse(moduleVersionRepository.findById(moduleVersion.getId()).orElseThrow().isDeprecated());
    }

    // Adding an implementation updates the version's implementation collection, so it now goes
    // through the version's update permission as well.
    private int createImplementation(String group) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"implementation","attributes":{"os":"linux","arch":"amd64","filename":"p.zip","downloadUrl":"https://example.com/p.zip","shasumsUrl":"https://example.com/SHA256SUMS","shasumsSignatureUrl":"https://example.com/SHA256SUMS.sig","shasum":"abc","keyId":"KEY","asciiArmor":"ARMOR","trustSignature":"","source":"test","sourceUrl":"https://example.com"}}}
                        """)
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/provider/" + provider.getId() + "/version/"
                        + firstProviderVersion.getId() + "/implementation")
                .then()
                .extract().statusCode();
    }

    @Test
    void providerManagerCanStillAddImplementations() {
        assertEquals(HttpStatus.CREATED.value(), createImplementation("TERRAKUBE_DEVELOPERS"));
    }

    @Test
    void teamWithoutManageProviderCannotAddImplementations() {
        assertEquals(HttpStatus.FORBIDDEN.value(), createImplementation(VIEW_ONLY_TEAM));
    }

    // Viewers must not be able to undo a removal by deleting the version (the refresh job would
    // re-import it as active) or by creating a second, active row for the same version number.
    @Test
    void teamWithoutManageModuleCannotDeleteModuleVersion() {
        given()
                .headers("Authorization", "Bearer " + generatePAT(VIEW_ONLY_TEAM))
                .when()
                .delete("/api/v1/organization/" + ORGANIZATION_ID + "/module/" + MODULE_ID + "/version/" + moduleVersion.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
        assertTrue(moduleVersionRepository.findById(moduleVersion.getId()).isPresent());
    }

    private int createProviderVersion(String group) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("""
                        {"data":{"type":"version","attributes":{"versionNumber":"2.0.0","protocols":"5.0"}}}
                        """)
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/provider/" + provider.getId() + "/version")
                .then()
                .extract().statusCode();
    }

    @Test
    void providerManagerCanCreateProviderVersion() {
        assertEquals(HttpStatus.CREATED.value(), createProviderVersion("TERRAKUBE_DEVELOPERS"));
    }

    @Test
    void teamWithoutManageProviderCannotCreateProviderVersion() {
        assertEquals(HttpStatus.FORBIDDEN.value(), createProviderVersion(VIEW_ONLY_TEAM));
    }

    // The registry relies on these exact filters and on the deprecationMessage alias, because the
    // typed client it uses has no deprecation fields.
    @Test
    void graphQlFiltersRemovedVersionsAndAliasesDeprecationMessage() {
        String query = """
                { "query": "{ organization(ids: [\\"%s\\"]) { edges { node { provider(filter: \\"name==deprecation-test\\") { edges { node { available: version(filter: \\"removed==false\\") { edges { node { versionNumber } } } notices: version(filter: \\"deprecated==true,removed==true\\") { edges { node { versionNumber protocols: deprecationMessage } } } } } } } } } }" }
                """.formatted(ORGANIZATION_ID);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/json")
                .body(query)
                .when()
                .post("/graphql/api/v1")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.organization.edges[0].node.provider.edges[0].node.available.edges.node.versionNumber",
                        containsInAnyOrder("1.0.0", "1.1.0"))
                .body("data.organization.edges[0].node.provider.edges[0].node.notices.edges.node.protocols",
                        containsInAnyOrder("Removal on 2026-12-31", "Broken release, use 1.3.0"));
    }
}
