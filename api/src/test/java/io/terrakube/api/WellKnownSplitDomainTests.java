package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

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
}
