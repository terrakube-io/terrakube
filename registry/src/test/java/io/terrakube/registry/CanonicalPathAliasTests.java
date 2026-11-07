package io.terrakube.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

/**
 * Registry paths are also served under /api/private-registry for single-domain hosts. The
 * split-domain side (no alias, unchanged discovery) is pinned in {@link WellKnownTests}.
 */
@TestPropertySource(properties = "io.terrakube.public-url=https://app.example.com")
class CanonicalPathAliasTests extends OpenRegistryApplicationTests {

    @ParameterizedTest
    @ValueSource(strings = {
        "/.well-known/terraform.json",
        "/terraform/modules/v1/sample/module/aws/versions",
        "/terraform/providers/v1/sample/provider/versions"
    })
    void canonicalAliasMatchesLegacyResponse(String path) {
        var legacy = given().when().get(path).then().extract();
        var canonical = given().when().get("/api/private-registry" + path).then().extract();
        assertEquals(legacy.statusCode(), canonical.statusCode(), path);
        if (legacy.statusCode() == 200) {
            // Error bodies carry a timestamp and the request path, so only compare successful ones.
            assertEquals(legacy.body().asString(), canonical.body().asString(), path);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/private-registry/actuator", "/api/private-registry/actuator/health"})
    void actuatorIsNotExposedUnderAlias(String path) {
        assertEquals(404, given().when().get(path).then().extract().statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/private-registry/..;/actuator/health", "/api/private-registry/%2e%2e/actuator/health"
    })
    void suspiciousRawPathsAreRejected(String path) {
        assertEquals(400, given().urlEncodingEnabled(false).when().get(path).then().extract().statusCode(), path);
    }
}
