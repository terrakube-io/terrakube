package io.terrakube.registry;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

@TestPropertySource(properties = {
    "io.terrakube.registry.login-broker-enabled=true",
    "io.terrakube.registry.login-api-url=https://api.example.test"
})
public class WellKnownBrokerEnabledTests extends OpenRegistryApplicationTests {

    @Test
    void brokerEnabled_pointsAtTerrakubeApi() {
        when()
                .get("/.well-known/terraform.json")
                .then()
                .statusCode(HttpStatus.SC_OK)
                .body("'login.v1'.client", equalTo("terraform-cli"))
                .body("'login.v1'.authz", equalTo("https://api.example.test/oauth/authorize"))
                .body("'login.v1'.token", equalTo("https://api.example.test/oauth/token"))
                .body("'login.v1'.ports", hasItems(10000, 10010));
    }
}
