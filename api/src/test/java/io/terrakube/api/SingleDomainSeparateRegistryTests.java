package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Single-domain mode with the registry on its own hostname: discovery leaves the registry out. */
@TestPropertySource(properties = {
    "io.terrakube.public-url=https://app.example.com",
    "io.terrakube.hostname=",
    "AzBuilderRegistry=https://registry.example.com"
})
class SingleDomainSeparateRegistryTests extends ServerApplicationTests {

    @Test
    void discoveryOmitsRegistryServices() {
        given().when().get("/.well-known/terraform.json").then().statusCode(200)
                .body("size()", equalTo(4))
                .body("'tfe.v2'", equalTo("/api/remote/tfe/v2/"))
                .body("$", not(hasKey("modules.v1")))
                .body("$", not(hasKey("providers.v1")));
    }
}
