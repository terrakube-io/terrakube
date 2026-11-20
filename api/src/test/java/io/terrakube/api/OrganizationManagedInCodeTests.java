package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.terrakube.api.rs.Organization;

// An organization kept in code names its repository, and a superuser says whether the settings only warn or lock.
class OrganizationManagedInCodeTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @AfterEach
    void restore() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
        organization.setManagedInCodeRepository(null);
        organization.setManagedInCodeMode(null);
        organizationRepository.save(organization);
    }

    private int patch(String token, String attributes) {
        return given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"organization\",\"id\":\"" + ORG + "\",\"attributes\":" + attributes + "}}")
                .when()
                .patch("/api/v1/organization/" + ORG)
                .then()
                .extract().statusCode();
    }

    private Organization stored() {
        return organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
    }

    @Test
    void anOrganizationIsNotKeptInCodeUntilSomeoneSaysSo() {
        assertThat(stored().getManagedInCodeRepository()).isNull();
        assertThat(stored().getManagedInCodeMode()).isNull();
    }

    @Test
    void aSuperuserSetsAndClearsTheRepositoryAndMode() {
        int set = patch(generatePAT("TERRAKUBE_ADMIN"),
                "{\"managedInCodeRepository\":\"https://github.com/example/tf-terrakube-admin\",\"managedInCodeMode\":\"readonly\"}");

        assertThat(set).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(stored().getManagedInCodeRepository()).isEqualTo("https://github.com/example/tf-terrakube-admin");
        assertThat(stored().getManagedInCodeMode()).isEqualTo("readonly");

        int cleared = patch(generatePAT("TERRAKUBE_ADMIN"), "{\"managedInCodeRepository\":null,\"managedInCodeMode\":null}");

        assertThat(cleared).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(stored().getManagedInCodeRepository()).isNull();
    }

    @Test
    void anotherModeIsRejected() {
        int status = patch(generatePAT("TERRAKUBE_ADMIN"), "{\"managedInCodeMode\":\"lockdown\"}");

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(stored().getManagedInCodeMode()).isNull();
    }

    @Test
    void aTeamMemberWhoIsNotASuperuserCannotChangeIt() {
        int status = patch(generatePAT("TERRAKUBE_DEVELOPERS"), "{\"managedInCodeRepository\":\"https://example.com/x\"}");

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(stored().getManagedInCodeRepository()).isNull();
    }
}
