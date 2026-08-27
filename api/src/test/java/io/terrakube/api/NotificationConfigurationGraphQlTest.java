package io.terrakube.api;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.workspace.Workspace;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class NotificationConfigurationGraphQlTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @Autowired
    NotificationConfigurationRepository notificationConfigurationRepository;
    @Autowired
    OrganizationRepository organizationRepository;
    @Autowired
    WorkspaceRepository workspaceRepository;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // Regression test: NotificationConfiguration.workspace was LAZY, and reading its id through
    // Elide's GraphQL relationship traversal (workspace { edges { node { id } } }) - as opposed
    // to a plain Java getter call, e.g. from a permission check - serialized the literal string
    // "null" instead of the real UUID. That silently broke every UI query that scopes
    // notification configs by workspace, since "null" never equals a real workspace id.
    @Test
    void graphQlServesTheRealWorkspaceIdNotTheLiteralStringNull() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();

        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("GraphQL Workspace Id Regression");
        configuration.setOrganization(organization);
        configuration.setWorkspace(workspace);
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("https://example.com/hook");
        configuration.setActive(true);
        configuration = notificationConfigurationRepository.saveAndFlush(configuration);

        String query = "{ \"query\": \"{ organization(ids: [\\\"" + ORGANIZATION_ID + "\\\"]) { edges { node { "
                + "notificationConfiguration { edges { node { id workspace { edges { node { id } } } } } } } } } }\" }";

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/json")
                .body(query)
                .when()
                .post("/graphql/api/v1")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body(
                        "data.organization.edges[0].node.notificationConfiguration.edges.find { it.node.id == '"
                                + configuration.getId() + "' }.node.workspace.edges[0].node.id",
                        org.hamcrest.Matchers.equalTo(WORKSPACE_ID));
    }
}
