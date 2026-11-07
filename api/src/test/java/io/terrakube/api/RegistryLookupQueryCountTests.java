package io.terrakube.api;

import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.repository.ProviderImplementationRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Implementation;
import io.terrakube.api.rs.provider.implementation.Version;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

// The registry asks the API for every provider version with its platforms, and for every module version, on each
// `terraform init`. The number of statements must not grow with the number of versions.
class RegistryLookupQueryCountTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final String ORGANIZATION_NAME = "gcp";

    // Same shape as the registry's SEARCH_PROVIDER_VERSIONS and SEARCH_ORGANIZATION_MODULE_VERSION queries.
    private static final String PROVIDER_VERSIONS = """
            { organization(filter: "name==%s") { edges { node { id name
              provider(filter: "name==%s") { edges { node { id name
                version(filter: "status!=removed") { edges { node { id versionNumber protocols
                  implementation { edges { node { id os arch } } } } } } } } } } } } }""";
    private static final String MODULE_VERSIONS = """
            { organization(filter: "name==%s") { edges { node { id name
              module(filter: "name==%s;provider==%s") { edges { node { id name provider
                version(filter: "status!=removed") { edges { node { id version } } } } } } } } } }""";

    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    ProviderRepository providerRepository;
    @Autowired
    ProviderVersionRepository providerVersionRepository;
    @Autowired
    ProviderImplementationRepository providerImplementationRepository;
    @Autowired
    ModuleRepository moduleRepository;
    @Autowired
    ModuleVersionRepository moduleVersionRepository;

    private final List<Provider> providers = new ArrayList<>();
    private final List<Module> modules = new ArrayList<>();


    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void cleanup() {
        for (Provider provider : providers) {
            for (Version version : providerVersionRepository.findAllByProviderId(provider.getId())) {
                providerImplementationRepository.deleteAll(providerImplementationRepository.findAllByVersionId(version.getId()));
                providerVersionRepository.delete(version);
            }
            providerRepository.delete(provider);
        }
        for (Module module : modules) {
            moduleVersionRepository.deleteAll(moduleVersionRepository.findAllByModuleId(module.getId()));
            moduleRepository.delete(module);
        }
    }

    private Organization organization() {
        return organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
    }

    private String seedProvider(int versions) {
        Provider provider = new Provider();
        provider.setName("mirror-batch-" + versions);
        provider.setOrganization(organization());
        provider = providerRepository.save(provider);
        providers.add(provider);
        for (int v = 0; v < versions; v++) {
            Version version = new Version();
            version.setProvider(provider);
            version.setVersionNumber("1.0." + v);
            version.setProtocols("5.0");
            version = providerVersionRepository.save(version);
            for (String os : List.of("linux", "darwin", "windows")) {
                Implementation implementation = new Implementation();
                implementation.setVersion(version);
                implementation.setOs(os);
                implementation.setArch("amd64");
                implementation.setFilename("terraform-provider-batch_1.0." + v + "_" + os + "_amd64.zip");
                implementation.setDownloadUrl("https://example.com/download");
                implementation.setShasumsUrl("https://example.com/shasums");
                implementation.setShasumsSignatureUrl("https://example.com/shasums.sig");
                implementation.setShasum("0000");
                implementation.setKeyId("KEY");
                implementation.setTrustSignature("");
                implementation.setSource("example");
                implementation.setSourceUrl("https://example.com");
                providerImplementationRepository.save(implementation);
            }
        }
        return provider.getName();
    }

    private String seedModule(int versions) {
        Module module = new Module();
        module.setName("module-batch-" + versions);
        module.setProvider("aws");
        module.setSource("https://git.example.test/acme/batch.git");
        module.setOrganization(organization());
        // Not due, so the refresh poller leaves it alone while statements are counted.
        module.setNextRefresh(new Date(System.currentTimeMillis() + 86_400_000L));
        module = moduleRepository.save(module);
        modules.add(module);
        for (int v = 0; v < versions; v++) {
            ModuleVersion version = new ModuleVersion();
            version.setModule(module);
            version.setVersion("1.0." + v);
            version.setCommit("0000000");
            moduleVersionRepository.save(version);
        }
        return module.getName();
    }

    private record Counts(long statements, long implementationFetches) {
    }

    private Counts count(String query, String versionsPath, int versions) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            given()
                    .headers("Authorization", "Bearer " + generateRegistryToken())
                    .contentType("application/json")
                    .body(Map.of("query", query))
                    .when()
                    .post("/graphql/api/v1")
                    .then()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.OK.value())
                    .body("$", not(hasKey("errors")))
                    .body(versionsPath, hasSize(versions));
            return new Counts(statistics.getPrepareStatementCount(), statistics
                    .getCollectionStatistics(Version.class.getName() + ".implementation").getFetchCount());
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void providerVersionsQueryDoesNotGrowWithVersionCount() {
        String path = "data.organization.edges[0].node.provider.edges[0].node.version.edges";
        Counts five = count(PROVIDER_VERSIONS.formatted(ORGANIZATION_NAME, seedProvider(5)), path, 5);
        Counts twenty = count(PROVIDER_VERSIONS.formatted(ORGANIZATION_NAME, seedProvider(20)), path, 20);

        assertTrue(twenty.statements() - five.statements() <= 2,
                "5 versions ran " + five.statements() + " statements, 20 versions ran " + twenty.statements());
        assertTrue(twenty.implementationFetches() <= 1,
                "implementation collections fetched one by one: " + twenty.implementationFetches());
    }

    @Test
    void moduleVersionsQueryDoesNotGrowWithVersionCount() {
        String path = "data.organization.edges[0].node.module.edges[0].node.version.edges";
        Counts five = count(MODULE_VERSIONS.formatted(ORGANIZATION_NAME, seedModule(5), "aws"), path, 5);
        Counts twenty = count(MODULE_VERSIONS.formatted(ORGANIZATION_NAME, seedModule(20), "aws"), path, 20);

        assertTrue(twenty.statements() - five.statements() <= 2,
                "5 versions ran " + five.statements() + " statements, 20 versions ran " + twenty.statements());
    }
}
