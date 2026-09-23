package io.terrakube.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "io.terrakube.token.login.enabled=true",
    "io.terrakube.token.login.api-url=https://api.example.test"
})
class WellKnownLoginBrokerEnabledTests extends ServerApplicationTests {

    @Test
    void brokerEnabled_pointsAtTerrakube() {
        given().when().get("/.well-known/terraform.json")
            .then().statusCode(200)
            .body("'login.v1'.client", equalTo("terraform-cli"))
            .body("'login.v1'.authz", equalTo("https://api.example.test/oauth/authorize"))
            .body("'login.v1'.token", equalTo("https://api.example.test/oauth/token"))
            .body("'login.v1'.grant_types", hasItem("authz_code"))
            .body("'login.v1'.ports", hasItems(10000, 10010));
    }
}
