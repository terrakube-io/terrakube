package io.terrakube.api;

import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.rs.token.pat.Pat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

class PatLastUsedTests extends ServerApplicationTests {

    @Autowired
    PatRepository patRepository;

    private static UUID jti(String token) {
        return UUID.fromString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void firstUseStampsLastUsedAt() {
        String token = generatePAT("TERRAKUBE_DEVELOPERS");
        UUID id = jti(token);
        assertNull(patRepository.findById(id).orElseThrow().getLastUsedAt());

        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(200);

        Pat pat = patRepository.findById(id).orElseThrow();
        assertNotNull(pat.getLastUsedAt());
    }

    @Test
    void recentUseIsNotRewritten() {
        String token = generatePAT("TERRAKUBE_DEVELOPERS");
        UUID id = jti(token);
        Pat pat = patRepository.findById(id).orElseThrow();
        Date pinned = new Date(System.currentTimeMillis() - 5 * 60 * 1000); // 5 min ago
        pat.setLastUsedAt(pinned);
        patRepository.save(pat);

        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(200);

        assertEquals(pinned.getTime() / 1000,
            patRepository.findById(id).orElseThrow().getLastUsedAt().getTime() / 1000);
    }
}
