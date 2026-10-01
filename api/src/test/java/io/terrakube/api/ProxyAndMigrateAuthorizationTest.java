package io.terrakube.api;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class ProxyAndMigrateAuthorizationTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    Workspace workspace;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();

        workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        workspace.setSource("https://github.com/AzBuilder/terrakube-docker-compose.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.2.5");
        workspace.setOrganization(organizationRepository.getReferenceById(UUID.fromString(ORGANIZATION_ID)));
        workspace = workspaceRepository.save(workspace);

        Variable variable = new Variable();
        variable.setKey("API_KEY");
        variable.setValue("secret-value");
        variable.setCategory(Category.ENV);
        variable.setSensitive(true);
        variable.setWorkspace(workspace);
        variableRepository.save(variable);
    }

    @AfterEach
    void tearDown() {
        workspace.setDeleted(true);
        workspaceRepository.save(workspace);
    }

    private String proxyHeaders() {
        return "{\"Authorization\":\"Bearer {{var.API_KEY}}\"}";
    }

    @Test
    void proxyRejectsUsersWithoutWorkspaceAccess() {
        given().headers("Authorization", "Bearer " + generatePAT("SOME_OTHER_GROUP"))
                .queryParam("targetUrl", "http://localhost:" + wireMockServer.port() + "/target")
                .queryParam("proxyheaders", proxyHeaders())
                .queryParam("workspaceId", workspace.getId().toString())
                .when().get("/proxy/v1")
                .then().statusCode(HttpStatus.FORBIDDEN.value());

        assertEquals(0, wireMockServer.getAllServeEvents().size());
    }

    @Test
    void proxyForwardsWithInjectedVariablesForWorkspaceManagers() {
        wireMockServer.stubFor(get(urlPathEqualTo("/target")).willReturn(aResponse().withStatus(200).withBody("ok")));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .queryParam("targetUrl", "http://localhost:" + wireMockServer.port() + "/target")
                .queryParam("proxyheaders", proxyHeaders())
                .queryParam("workspaceId", workspace.getId().toString())
                .when().get("/proxy/v1")
                .then().statusCode(HttpStatus.OK.value());

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/target"))
                .withHeader("Authorization", equalTo("Bearer secret-value")));
    }

    @Test
    void migrateRequiresAdminGroup() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/migrate/v1/workspace/" + workspace.getId() + "/moveTo/" + UUID.randomUUID())
                .then().statusCode(HttpStatus.FORBIDDEN.value());

        assertEquals(ORGANIZATION_ID,
                workspaceRepository.findById(workspace.getId()).orElseThrow().getOrganization().getId().toString());
    }

    @Test
    void migrateIsAllowedForAdmins() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/migrate/v1/workspace/" + UUID.randomUUID() + "/moveTo/" + UUID.randomUUID())
                .then().statusCode(HttpStatus.OK.value());
    }
}
