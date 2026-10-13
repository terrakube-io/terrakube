package io.terrakube.api;

import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

public class SshTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchSshAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/ssh")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createSshAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"ssh\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SSH RANDOM DATA\",\n" +
                        "      \"description\": \"SSH KEY DESCRIPTION\",\n" +
                        "      \"privateKey\": \"12345\", \n" +
                        "      \"sshType\": \"rsa\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/ssh")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("SSH RANDOM DATA"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void searchSshAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/ssh")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createSshAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"ssh\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SSH KEY NAME\",\n" +
                        "      \"description\": \"SSH KEY DESCRIPTION\",\n" +
                        "      \"privateKey\": \"{{sshPrivateKey}}\", \n" +
                        "      \"sshType\": \"{{sshKeyType}}\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/ssh")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void readSshPrivateKey() {
        //Able to create the ssh connection
        String sshId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"ssh\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SSH RANDOM DATA\",\n" +
                        "      \"description\": \"SSH KEY DESCRIPTION\",\n" +
                        "      \"privateKey\": \"12345\", \n" +
                        "      \"sshType\": \"rsa\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/ssh")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("SSH RANDOM DATA"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        //Read the vcs connection as TERRAKUBE_DEVELOPERS does not include the access token
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/ssh/"+sshId)
                .then()
                .assertThat()
                .body("data.attributes", not(hasKey("privateKey")))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        //Read the vcs connection as TERRAKUBE_ADMIN does not include the access token
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/ssh/"+sshId)
                .then()
                .assertThat()
                .body("data.attributes", not(hasKey("privateKey")))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        //Read the vcs connection as the registry include the access token
        given()
                .headers("Authorization", "Bearer " + generateRegistryToken())
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/ssh/"+sshId)
                .then()
                .assertThat()
                .body("data.attributes", hasKey("privateKey"))
                .body("data.attributes.privateKey", IsEqual.equalTo("12345"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }
}
