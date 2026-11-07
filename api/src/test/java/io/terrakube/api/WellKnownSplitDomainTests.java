package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the split-domain discovery document (TerrakubePublicUrl unset): it must stay exactly as in
 * earlier releases.
 */
class WellKnownSplitDomainTests extends ServerApplicationTests {

    @Test
    void splitDomainDiscoveryIsUnchanged() {
        given().when().get("/.well-known/terraform.json").then().statusCode(200)
                .body("size()", equalTo(4))
                .body("'state.v2'", equalTo("/remote/state/v2/"))
                .body("'tfe.v2'", equalTo("/remote/tfe/v2/"))
                .body("'tfe.v2.1'", equalTo("/remote/tfe/v2/"))
                .body("'login.v1'.client", equalTo("example-app"))
                .body("'login.v1'.grant_types", contains("authz_code", "openid", "profile", "email", "offline_access", "groups"))
                .body("'login.v1'.authz", equalTo("http://localhost:5556/dev/auth?scope=openid+profile+email+offline_access+groups"))
                .body("'login.v1'.token", equalTo("http://localhost:5556/dev/token"))
                .body("'login.v1'.ports", contains(10000, 10001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/pat/v1", "/api/access-token/v1/teams", "/api/remote/tfe/v2/ping"})
    void canonicalAliasesAreOffWithoutPublicUrl(String path) {
        // Split-domain installs must not grow /api/<legacy> next to the legacy paths that ingress or
        // WAF rules match on.
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get(path).then().statusCode(404);
    }
}
