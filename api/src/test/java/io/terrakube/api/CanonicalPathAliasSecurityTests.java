package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.restassured.specification.RequestSpecification;

/**
 * Every legacy path is also served as /api/&lt;legacy&gt; for single-domain hosts, with the same
 * authorization: the forward happens before Spring Security evaluates the request.
 */
class CanonicalPathAliasSecurityTests extends ServerApplicationTests {

    private static final String ID = "00000000-0000-0000-0000-000000000000";

    static Stream<Arguments> legacyPaths() {
        return Stream.of(
                Arguments.of("GET", "/tfstate/v1/organization/" + ID + "/workspace/" + ID + "/state/terraform.tfstate", false),
                Arguments.of("GET", "/pat/v1", false),
                Arguments.of("GET", "/pat/v1", true),
                Arguments.of("POST", "/webhook/v1/" + ID, false),
                Arguments.of("GET", "/callback/v1/vcs/" + ID + "?code=x&state=y", false),
                Arguments.of("GET", "/.well-known/terraform.json", false),
                Arguments.of("GET", "/remote/tfe/v2/ping", false),
                Arguments.of("PUT", "/remote/tfe/v2/configuration-versions/" + ID, false),
                Arguments.of("POST", "/oauth/token", false),
                Arguments.of("GET", "/app/org/ws/runs/1", false),
                Arguments.of("GET", "/terraform/index.json", false),
                Arguments.of("GET", "/access-token/v1/teams", false));
    }

    @ParameterizedTest(name = "{0} {1} authenticated={2}")
    @MethodSource("legacyPaths")
    void canonicalAliasMatchesLegacyStatus(String method, String path, boolean authenticated) {
        int legacy = call(method, path, authenticated);
        int canonical = call(method, "/api" + path, authenticated);
        assertEquals(legacy, canonical, "status for /api" + path + " differs from " + path);
    }

    @Test
    void protectedPathStaysProtectedUnderAlias() {
        assertEquals(401, call("GET", "/api/pat/v1", false));
    }

    @Test
    void permitAllPathStaysOpenUnderAlias() {
        assertNotEquals(401, call("POST", "/api/webhook/v1/" + ID, false));
        given().when().get("/api/.well-known/terraform.json").then().statusCode(200);
    }

    @Test
    void elideJsonApiIsNotRewritten() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get("/api/v1/organization")
                .then().statusCode(200).body("data", org.hamcrest.Matchers.notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/actuator", "/api/actuator/health", "/api/actuator/prometheus"})
    void actuatorIsNotExposedUnderAlias(String path) {
        assertEquals(404, call("GET", path, false));
    }

    @Test
    void legacyActuatorStillWorks() {
        // Health may report DOWN in tests (mocked Redis); it only has to stay reachable.
        assertNotEquals(404, call("GET", "/actuator/health", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/..;/actuator/health", "/api/%2e%2e/actuator/health", "/api/%2Fadmin/v1/scheduler/reconciliation",
        "/api/pat/v1;x=y"
    })
    void suspiciousRawPathsAreRejected(String path) {
        int status = given().urlEncodingEnabled(false).redirects().follow(false)
                .when().get(path).then().extract().statusCode();
        assertEquals(400, status, path);
    }

    private int call(String method, String path, boolean authenticated) {
        RequestSpecification spec = given().urlEncodingEnabled(false).redirects().follow(false);
        if (authenticated) {
            spec = spec.header("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"));
        }
        if (!"GET".equals(method)) {
            spec = spec.contentType("application/json").body("{}");
        }
        return spec.when().request(method, path).then().extract().statusCode();
    }
}
