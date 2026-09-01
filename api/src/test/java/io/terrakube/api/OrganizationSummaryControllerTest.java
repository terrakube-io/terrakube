package io.terrakube.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

class OrganizationSummaryControllerTest extends ServerApplicationTests {

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void returnsOrganizationMetadataAndAggregatedWorkspaceStatusForOrganizationMember() {
        given()
                .header("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/ui/v1/organizations/summary")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("find { it.name == 'simple' }.description", equalTo("simple sample organization"))
                .body("find { it.name == 'simple' }.executionMode", equalTo("remote"))
                .body("find { it.name == 'simple' }.workspaceCount", equalTo(4))
                .body("find { it.name == 'simple' }.statusCounts.NeverExecuted", equalTo(4));
    }

    @Test
    void limitedAccessOnlyReceivesTheOrganizationAndWorkspaceItCanRead() {
        given()
                .header("Authorization", "Bearer " + generatePAT("LIMITED_ACCESS"))
                .when()
                .get("/ui/v1/organizations/summary")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("name", contains("simple"))
                .body("workspaceCount", contains(1))
                .body("statusCounts.NeverExecuted", contains(1));
    }

    @Test
    void userWithoutAnyOrganizationGrantReceivesNoSummaries() {
        given()
                .header("Authorization", "Bearer " + generatePAT("FAKE_ADMIN"))
                .when()
                .get("/ui/v1/organizations/summary")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("$", empty());
    }
}
