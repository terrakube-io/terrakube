package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import io.terrakube.api.plugin.state.RemoteTfeService;

/**
 * Migrating install: TerrakubePublicUrl and TerrakubeHostname both set. Discovery and the /api aliases
 * switch to single-domain, while generated absolute URLs stay on the old API host so clients that
 * only know that host keep working until TerrakubeHostname is removed.
 */
@TestPropertySource(properties = {
    "io.terrakube.public-url=https://app.example.com",
    "io.terrakube.hostname=api.example.com"
})
class SingleDomainMigrationTests extends ServerApplicationTests {

    @Autowired
    RemoteTfeService remoteTfeService;

    @Test
    void discoveryAdvertisesApiPathsButGeneratedUrlsKeepTheOldHost() {
        given().when().get("/.well-known/terraform.json").then().statusCode(200)
                .body("'state.v2'", equalTo("/api/remote/state/v2/"))
                .body("'tfe.v2'", equalTo("/api/remote/tfe/v2/"));
        assertEquals("https://api.example.com", ReflectionTestUtils.getField(remoteTfeService, "apiBaseUrl"));
    }

    @Test
    void canonicalAliasesAreOn() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get("/api/pat/v1").then().statusCode(202);
    }
}
