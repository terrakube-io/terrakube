package io.terrakube.api;

import io.terrakube.api.repository.VcsRepository;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

class VcsTests extends ServerApplicationTests{

    @Autowired
    private VcsRepository vcsRepository;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    @Test
    void searchVcsAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }
    @Test
    void searchVcsAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createVcsAsOrgMember() {
        String vcsId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"vcs\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"githubConnection\",\n" +
                        "      \"description\": \"vcsGitHubDescription\",\n" +
                        "      \"vcsType\": \"GITHUB\",\n" +
                        "      \"clientId\": \"12345\",\n" +
                        "      \"clientSecret\": \"12345\",\n" +
                        "      \"accessToken\": \"12345\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("githubConnection"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs/" + vcsId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }
    @Test
    void createVcsAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"vcs\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"githubConnection\",\n" +
                        "      \"description\": \"vcsGitHubDescription\",\n" +
                        "      \"vcsType\": \"GITHUB\",\n" +
                        "      \"clientId\": \"12345\",\n" +
                        "      \"clientSecret\": \"12345\",\n" +
                        "      \"accessToken\": \"12345\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void readVCSAsInternal() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void readVCSAsAccessToken() {
        //Able to create the vcs connection
        String vcsId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"vcs\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"githubConnection\",\n" +
                        "      \"description\": \"vcsGitHubDescription\",\n" +
                        "      \"vcsType\": \"GITHUB\",\n" +
                        "      \"clientId\": \"12345\",\n" +
                        "      \"clientSecret\": \"12345\",\n" +
                        "      \"accessToken\": \"12345\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("githubConnection"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        //Read the vcs connection as TERRAKUBE_DEVELOPERS does not include the access token
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs/"+vcsId)
                .then()
                .assertThat()
                .body("data.attributes", not(hasKey("accessToken")))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        //Read the vcs connection as TERRAKUBE_ADMIN does not include the access token
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs/"+vcsId)
                .then()
                .assertThat()
                .body("data.attributes", not(hasKey("accessToken")))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        //Read the vcs connection as the registry include the access token
        given()
                .headers("Authorization", "Bearer " + generateSystemToken())
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs/"+vcsId)
                .then()
                .assertThat()
                .body("data.attributes", hasKey("accessToken"))
                .body("data.attributes.accessToken", IsEqual.equalTo("12345"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        // Delete the test vcs connection
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs/"+vcsId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }
}
