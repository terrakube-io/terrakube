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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
