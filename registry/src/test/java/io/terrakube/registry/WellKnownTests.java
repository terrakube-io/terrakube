package io.terrakube.registry;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

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
                .body("'login.v1'.ports", hasItems(10000, 10010));
    }
}
