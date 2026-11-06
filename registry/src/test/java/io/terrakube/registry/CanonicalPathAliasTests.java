package io.terrakube.registry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Registry paths are also served under /api/private-registry for single-domain hosts, and the
 * split-domain discovery document stays exactly as before.
 */
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

    @Test
    void splitDomainDiscoveryIsUnchanged() {
        given().when().get("/.well-known/terraform.json").then().statusCode(200)
                .body("'modules.v1'", equalTo("http://localhost/terraform/modules/v1/"))
                .body("'providers.v1'", equalTo("http://localhost/terraform/providers/v1/"))
                .body("'login.v1'.client", equalTo("sample"))
                .body("'login.v1'.grant_types", contains("authz_code", "openid", "profile", "email", "offline_access", "groups"))
                .body("'login.v1'.authz", equalTo("https://sample.com/auth?scope=openid+profile+email+offline_access+groups"))
                .body("'login.v1'.token", equalTo("https://sample.com/token"))
                .body("'login.v1'.ports", contains(10000, 10001))
                .body("size()", equalTo(3));
    }
}
