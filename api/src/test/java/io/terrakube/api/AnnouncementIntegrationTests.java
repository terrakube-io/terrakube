package io.terrakube.api;

import io.terrakube.api.repository.AnnouncementRepository;
import io.terrakube.api.rs.announcement.Announcement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

public class AnnouncementIntegrationTests extends ServerApplicationTests {

    private static final String ORGANIZATION = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String GROUP_SUPERUSER = "TERRAKUBE_ADMIN";
    private static final String GROUP_ORG_ADMIN = "TERRAKUBE_DEVELOPERS";
    private static final String GROUP_NON_ADMIN = "PROJECT_TEAM_MEMBER";

    @Autowired
    private AnnouncementRepository announcementRepository;

    private Set<UUID> announcementsBefore;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        announcementsBefore = announcementRepository.findAll().stream()
                .map(Announcement::getId)
                .collect(Collectors.toSet());
    }

    @AfterEach
    public void cleanup() {
        announcementRepository.findAll().stream()
                .filter(a -> !announcementsBefore.contains(a.getId()))
                .forEach(announcementRepository::delete);
    }

    @Test
    void publicEndpointReturnsActiveLoginAnnouncementsWithoutAuth() {
        given()
                .when()
                .get("/public/v1/announcements")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body("size()", greaterThanOrEqualTo(1))
                .body("title", hasItem("Welcome to Terrakube Demo Environment"))
                .body("severity", hasItem("info"));
    }

    @Test
    void superuserCanCreateGlobalAnnouncement() {
        String payload = "{\"data\":{\"type\":\"announcement\",\"attributes\":{" +
                "\"title\":\"Test Global Alert\"," +
                "\"message\":\"Platform maintenance scheduled\"," +
                "\"severity\":\"critical\"," +
                "\"scope\":\"GLOBAL\"," +
                "\"active\":true," +
                "\"dismissible\":false," +
                "\"displayOnLogin\":false" +
                "}}}";

        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_SUPERUSER))
                .contentType("application/vnd.api+json")
                .body(payload)
                .when()
                .post("/api/v1/announcement")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.attributes.title", equalTo("Test Global Alert"))
                .body("data.attributes.severity", equalTo("critical"))
                .body("data.attributes.scope", equalTo("GLOBAL"));
    }

    @Test
    void orgAdminCanCreateOrgAnnouncement() {
        String payload = "{\"data\":{\"type\":\"announcement\",\"attributes\":{" +
                "\"title\":\"Test Org Notice\"," +
                "\"message\":\"Please update credentials\"," +
                "\"severity\":\"warning\"," +
                "\"scope\":\"ORGANIZATION\"," +
                "\"active\":true," +
                "\"dismissible\":true" +
                "},\"relationships\":{" +
                "\"organization\":{\"data\":{\"type\":\"organization\",\"id\":\"" + ORGANIZATION + "\"}}" +
                "}}}";

        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_ORG_ADMIN))
                .contentType("application/vnd.api+json")
                .body(payload)
                .when()
                .post("/api/v1/announcement")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.attributes.title", equalTo("Test Org Notice"))
                .body("data.attributes.severity", equalTo("warning"))
                .body("data.attributes.scope", equalTo("ORGANIZATION"));
    }

    @Test
    void nonAdminCannotCreateAnnouncement() {
        String payload = "{\"data\":{\"type\":\"announcement\",\"attributes\":{" +
                "\"title\":\"Unauthorized Notice\"," +
                "\"message\":\"Should be rejected\"," +
                "\"severity\":\"info\"," +
                "\"scope\":\"ORGANIZATION\"" +
                "},\"relationships\":{" +
                "\"organization\":{\"data\":{\"type\":\"organization\",\"id\":\"" + ORGANIZATION + "\"}}" +
                "}}}";

        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_NON_ADMIN))
                .contentType("application/vnd.api+json")
                .body(payload)
                .when()
                .post("/api/v1/announcement")
                .then()
                .assertThat()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void userCanListAnnouncements() {
        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_SUPERUSER))
                .when()
                .get("/api/v1/announcement")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body("data.size()", greaterThanOrEqualTo(1));
    }
}

