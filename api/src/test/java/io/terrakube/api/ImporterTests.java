package io.terrakube.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;

public class ImporterTests extends ServerApplicationTests {

    @Test
    void forbiddenGetImporterWorkspaces() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .header("X-TFC-Url", "https://fake.com")
                .header("X-TFC-Token", "12345")
                .header("Content-Type", "application/json")
                .queryParam("organization", "my-org")
                .when()
                .get("/importer/tfcloud/workspaces")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void forbiddenPostImporterWorkspaces() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .header("X-TFC-Url", "https://fake.com")
                .header("X-TFC-Token", "12345")
                .header("Content-Type", "application/json")
                .body("{}")
                .when()
                .post("/importer/tfcloud/workspaces")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }
}
