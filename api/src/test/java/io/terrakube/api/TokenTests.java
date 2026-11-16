package io.terrakube.api;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class TokenTests extends ServerApplicationTests {

    @Autowired
    TeamTokenService teamTokenService;

    @Value("${io.terrakube.token.pat}")
    String base64Key;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void createTokenWithPatIsForbidden() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .headers("Content-Type", "application/vnd.api+json")
                .when()
                .body("{\"description\":\"12345\",\"days\":0}")
                .post("/pat/v1")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createTeamTokenWithPatIsForbidden() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .headers("Content-Type", "application/vnd.api+json")
                .when()
                .body("{\"description\":\"12345\",\"days\":0,\"minutes\":0,\"hours\":0,\"group\":\"TERRAKUBE_DEVELOPERS\"}")
                .post("/access-token/v1/teams")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createTeamTokenWithTeamTokenIsForbidden() {
        String teamToken = teamTokenService.createToken(1, 0, 0, "12345", "TERRAKUBE_DEVELOPERS", "test@terrakube.io");

        given()
                .headers("Authorization", "Bearer " + teamToken)
                .headers("Content-Type", "application/vnd.api+json")
                .when()
                .body("{\"description\":\"12345\",\"days\":0,\"minutes\":0,\"hours\":0,\"group\":\"TERRAKUBE_DEVELOPERS\"}")
                .post("/access-token/v1/teams")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void deleteTeamTokenRequiresGroupMembership() throws JsonProcessingException {
        String teamToken = teamTokenService.createToken(1, 0, 0, "12345", "TERRAKUBE_DEVELOPERS", "test@terrakube.io");
        String tokenId = (String) decodePayload(teamToken).get("jti");

        given()
                .headers("Authorization", "Bearer " + generatePAT("OTHER_TEAM"))
                .when()
                .delete("/access-token/v1/teams/" + tokenId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + teamToken).when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
                .then()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/access-token/v1/teams/" + tokenId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.ACCEPTED.value());

        given()
                .headers("Authorization", "Bearer " + teamToken).when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void terrakubeTokenWithoutStoredJtiIsRejected() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(base64Key));
        String token = Jwts.builder()
                .issuer("Terrakube")
                .subject("Forged (Token)")
                .audience().add("Terrakube").and()
                .id(UUID.randomUUID().toString())
                .claim("email", "test@terrakube.io")
                .claim("groups", List.of("TERRAKUBE_DEVELOPERS"))
                .issuedAt(new Date())
                .signWith(key)
                .compact();

        given()
                .headers("Authorization", "Bearer " + token).when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    private Map<String, Object> decodePayload(String token) throws JsonProcessingException {
        String[] chunks = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(chunks[1]));
        return new ObjectMapper().readValue(payload, HashMap.class);
    }

}
