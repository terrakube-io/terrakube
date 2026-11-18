package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Mockito.when;

class OrganizationTests extends ServerApplicationTests {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchOrganizationAsAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN")).when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
                .then()
                .assertThat().body("data.attributes.name", equalTo("simple"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createOrganizationAsAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"organization\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"Terrakube\",\n" +
                        "      \"description\": \"Terrakube organization\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/")
                .then()
                .assertThat().body("data.attributes.name", equalTo("Terrakube"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void createOrganizationWithNameDifferingOnlyByCaseIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"organization\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SIMPLE\",\n" +
                        "      \"description\": \"Case variant of the simple organization\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/")
                .then()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.CONFLICT.value());
    }

    @Test
    void renameOrganizationToNameDifferingOnlyByCaseIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"organization\",\n" +
                        "    \"id\": \"3a130a1c-d96f-4f99-83b8-58d472567e3a\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"Simple\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/3a130a1c-d96f-4f99-83b8-58d472567e3a")
                .then()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.CONFLICT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN")).when()
                .get("/api/v1/organization/3a130a1c-d96f-4f99-83b8-58d472567e3a")
                .then()
                .assertThat().body("data.attributes.name", equalTo("aws"))
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void disablingAnOrganizationSoftDeletesItsWorkspaces() {
        // The soft delete runs from the field-level UPDATE binding on "disabled", so go through Elide's
        // JSON:API PATCH rather than calling the hook directly.
        Organization organization = new Organization();
        organization.setName("disable-" + UUID.randomUUID().toString().substring(0, 8));
        organization.setDescription("Disabled with a workspace");
        organization = organizationRepository.save(organization);

        Workspace workspace = new Workspace();
        workspace.setName("disable-" + UUID.randomUUID().toString().substring(0, 8));
        workspace.setSource("https://github.com/terrakube-io/terraform-sample.git");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.save(workspace);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"organization\",\n" +
                        "    \"id\": \"" + organization.getId() + "\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"disabled\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + organization.getId())
                .then()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // The workspace's @SQLRestriction hides soft-deleted rows from the repository, so read the column directly.
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            Object deleted = entityManager
                    .createNativeQuery("SELECT deleted FROM workspace WHERE id = :id")
                    .setParameter("id", workspace.getId().toString())
                    .getSingleResult();
            assertThat(deleted).isEqualTo(true);
        } finally {
            entityManager.close();
        }
    }

    @Test
    void updatingOtherFieldsWorksNextToALegacyCaseVariant() {
        // Existing PostgreSQL installs can already hold "AWS" next to the seeded "aws"; both must stay editable.
        Organization caseVariant = new Organization();
        caseVariant.setName("AWS");
        caseVariant.setDescription("Legacy case variant");
        caseVariant = organizationRepository.save(caseVariant);

        try {
            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                    .body("{\n" +
                            "  \"data\": {\n" +
                            "    \"type\": \"organization\",\n" +
                            "    \"id\": \"3a130a1c-d96f-4f99-83b8-58d472567e3a\",\n" +
                            "    \"attributes\": {\n" +
                            "      \"description\": \"Updated next to a case variant\"\n" +
                            "    }\n" +
                            "  }\n" +
                            "}")
                    .when()
                    .patch("/api/v1/organization/3a130a1c-d96f-4f99-83b8-58d472567e3a")
                    .then()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.NO_CONTENT.value());

            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                    .body("{\n" +
                            "  \"data\": {\n" +
                            "    \"type\": \"organization\",\n" +
                            "    \"id\": \"" + caseVariant.getId() + "\",\n" +
                            "    \"attributes\": {\n" +
                            "      \"disabled\": true\n" +
                            "    }\n" +
                            "  }\n" +
                            "}")
                    .when()
                    .patch("/api/v1/organization/" + caseVariant.getId())
                    .then()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.NO_CONTENT.value());
        } finally {
            organizationRepository.deleteById(caseVariant.getId());
        }
    }

    @Test
    void searchOrganizationAsNonMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_ADMIN")).when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void searchOrganizationInvalidToken() {
        given()
                .headers("Authorization", "Bearer ").when()
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

}
