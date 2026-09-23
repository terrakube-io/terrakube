package io.terrakube.api;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;

class WellKnownLoginBrokerTests extends ServerApplicationTests {

    @Test
    void brokerDisabled_pointsAtDex() {
        given().when().get("/.well-known/terraform.json")
            .then().statusCode(200)
            .body("'login.v1'.token", containsString("/dev"))
            .body("'login.v1'.ports", hasItems(10000, 10010));
    }
}
