package io.terrakube.registry;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.contains;

public class WellKnownTests extends OpenRegistryApplicationTests {

    @Test
    void providerApiGetTest() {
        when()
                .get("/.well-known/terraform.json")
                .then()
                .log().all()
                .statusCode(HttpStatus.SC_OK);
    }

    @Test
    void brokerDisabled_pointsAtDex() {
        when()
                .get("/.well-known/terraform.json")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("'modules.v1'", containsString("/terraform/modules/v1/"))
                .body("'login.v1'.authz", containsString("https://sample.com/auth"))
                .body("'login.v1'.ports", contains(10000, 10001));
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

    @Test
    void canonicalAliasIsOffWithoutPublicUrl() {
        // Split-domain installs must not grow /api/private-registry/<legacy> next to the legacy paths.
        when().get("/api/private-registry/.well-known/terraform.json").then().statusCode(HttpStatus.SC_NOT_FOUND);
    }
}
