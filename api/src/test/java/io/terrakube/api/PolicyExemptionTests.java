package io.terrakube.api;

import io.terrakube.api.repository.PolicyExemptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

class PolicyExemptionTests extends ServerApplicationTests {

    // Seeded by demo-data/simple-governance.xml; its TERRAKUBE_ADMIN team can manage policies.
    private static final String ORGANIZATION_ID = "70000000-0000-0000-0000-000000000001";
    private static final String POLICY_SET_ID = "70000000-0000-0000-0000-000000000040";

    @Autowired
    PolicyExemptionRepository policyExemptionRepository;

    private final List<UUID> createdExemptions = new ArrayList<>();

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    public void cleanup() {
        createdExemptions.forEach(policyExemptionRepository::deleteById);
        createdExemptions.clear();
    }

    @Test
    void createExemptionWithTicketReferenceSucceeds() {
        String id = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body(exemptionBody("\"ticketReference\": \"SEC-101\", \"justification\": \"Accepted risk\""))
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/policyExemption")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.attributes.ticketReference", equalTo("SEC-101"))
                .extract().path("data.id");
        createdExemptions.add(UUID.fromString(id));
    }

    @Test
    void createExemptionWithoutTicketReferenceIsRejectedWithBadRequest() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body(exemptionBody("\"justification\": \"Accepted risk\""))
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/policyExemption")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("errors[0].detail", containsString("ticketReference"));
    }

    @Test
    void createExemptionWithBlankTicketReferenceIsRejectedWithBadRequest() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body(exemptionBody("\"ticketReference\": \"   \", \"justification\": \"Accepted risk\""))
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/policyExemption")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("errors[0].detail", containsString("ticketReference"));
    }

    @Test
    void notNullColumnViolationIsRejectedWithBadRequestNotConflict() {
        // justification has no bean validation, so a missing value reaches the database NOT NULL
        // constraint and goes through DataIntegrityExceptionMapper.
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body(exemptionBody("\"ticketReference\": \"SEC-101\""))
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/policyExemption")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    private static String exemptionBody(String extraAttributes) {
        return "{\n" +
                "  \"data\": {\n" +
                "    \"type\": \"policy_exemption\",\n" +
                "    \"attributes\": {\n" +
                "      \"ruleId\": \"password_length\",\n" +
                "      " + extraAttributes + "\n" +
                "    },\n" +
                "    \"relationships\": {\n" +
                "      \"policySet\": {\n" +
                "        \"data\": { \"type\": \"policy_set\", \"id\": \"" + POLICY_SET_ID + "\" }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }
}
