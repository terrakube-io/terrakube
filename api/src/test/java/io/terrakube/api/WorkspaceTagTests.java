package io.terrakube.api;

import io.restassured.response.Response;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Key/value workspace tags over the Elide APIs the UI uses.
 *
 * <p>The demo organization ships three workspaces with the key-only tags of the original feature plus two
 * keys that carry a value:
 *
 * <pre>
 * simple_tag1 : development, networking, env=production,  team=infra
 * simple_tag2 : development, networking, env=development, team=platform
 * simple_tag3 : development,             env=development
 * </pre>
 */
class WorkspaceTagTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String SIMPLE_TAG_2 = "5a7873bd-9fd3-4193-b3df-33ba586fb146";
    private static final String SIMPLE_TAG_3 = "24480d33-2649-4c34-aabd-cbc988eb6265";
    private static final String ENV_TAG = "3e8b0c3a-6a0c-4d5e-9d6a-2b4f9a1c7d10";
    private static final String TEAM_TAG = "9c1f5d42-8e73-4a61-bb28-5d0c7f36a4e1";
    private static final String DEVELOPMENT_TAG = "58529721-425e-44d7-8b0d-1d515043c2f7";

    private static final String WORKSPACE_QUERY = """
            query WorkspacePage($organizationIds: [String], $filter: String) {
              organization(ids: $organizationIds) {
                edges {
                  node {
                    workspace(first: "50", after: "0", filter: $filter, sort: "name,id") {
                      edges { node { id name workspaceTag { edges { node { tagId value } } } } }
                      pageInfo { totalRecords }
                    }
                  }
                }
              }
            }
            """;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void graphQlReturnsTheValueOfEveryTagBinding() {
        Response response = search("name==\"simple_tag1\"");

        List<Map<String, String>> bindings = response.jsonPath().getList(
                "data.organization.edges[0].node.workspace.edges[0].node.workspaceTag.edges.node");
        assertThat(bindings).as(response.asPrettyString())
                .contains(Map.of("tagId", ENV_TAG, "value", "production"))
                .contains(Map.of("tagId", TEAM_TAG, "value", "infra"));
        // Key-only tags keep a null value, which is what the UI renders as a plain chip.
        assertThat(bindings)
                .anyMatch(binding -> DEVELOPMENT_TAG.equals(binding.get("tagId")) && binding.get("value") == null);
    }

    @Test
    void oneKeyValuePairIsMatchedOnTheSameBinding() {
        // simple_tag1 has env=production and team=infra: a filter for env=development must not match it
        // through its team binding, which is what makes a single pair safe to send as one RSQL filter.
        assertThat(names(search(pair(ENV_TAG, "development")))).containsExactly("simple_tag2", "simple_tag3");
        assertThat(names(search(pair(TEAM_TAG, "infra")))).containsExactly("simple_tag1");
        assertThat(names(search("workspaceTag.tagId==\"" + ENV_TAG + "\"")))
                .containsExactly("simple_tag1", "simple_tag2", "simple_tag3");
    }

    @Test
    void twoKeyValuePairsCannotBeAndedInOneFilter() {
        // Elide collapses every predicate on the same to-many path into a single join alias, so an AND of two
        // pairs asks a single binding row to carry both keys and always comes back empty. The UI therefore
        // resolves the ids of each pair first and intersects them.
        assertThat(names(search(pair(ENV_TAG, "development") + ";" + pair(TEAM_TAG, "platform")))).isEmpty();

        // An OR of the same two pairs is expressed correctly, which is what the id resolution query relies on.
        assertThat(names(search("(" + pair(ENV_TAG, "development") + "),(" + pair(TEAM_TAG, "infra") + ")")))
                .containsExactly("simple_tag1", "simple_tag2", "simple_tag3");
    }

    @Test
    void intersectingTheIdsOfEachPairAppliesTheFilterAsAnAnd() {
        List<String> envMatches = ids(search(pair(ENV_TAG, "development")));
        List<String> teamMatches = ids(search(pair(TEAM_TAG, "platform")));
        List<String> intersection = envMatches.stream().filter(teamMatches::contains).toList();
        assertThat(intersection).containsExactly(SIMPLE_TAG_2);

        String filter = "id=in=(\"" + String.join("\",\"", intersection) + "\")";
        assertThat(names(search(filter))).containsExactly("simple_tag2");
        // The intersection is combined with the rest of the filters, so a search term still applies.
        assertThat(names(search(filter + ";name=ini=\"*tag1*\""))).isEmpty();
    }

    @Test
    void tagValueRoundTripsOverJsonApi() {
        String tagId = createTag("round-trip-key");
        String bindingId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/vnd.api+json")
                .body(binding(tagId, "first"))
                .when()
                .post(workspaceTagPath(SIMPLE_TAG_3))
                .then()
                .body("data.attributes.value", Matchers.equalTo("first"))
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspacetag\",\"id\":\"" + bindingId
                        + "\",\"attributes\":{\"value\":\"second\"}}}")
                .when()
                .patch(workspaceTagPath(SIMPLE_TAG_3) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get(workspaceTagPath(SIMPLE_TAG_3) + "/" + bindingId)
                .then()
                .body("data.attributes.value", Matchers.equalTo("second"))
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete(workspaceTagPath(SIMPLE_TAG_3) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/" + ORGANIZATION_ID + "/tag/" + tagId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void aTagCanBeAddedAndClearedWithoutAValue() {
        // The workspace UI sends an explicit null for a key-only tag, and for a value it has emptied.
        String tagId = createTag("key-only-key");
        String bindingId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspacetag\",\"attributes\":{\"tagId\":\"" + tagId
                        + "\",\"value\":null}}}")
                .when()
                .post(workspaceTagPath(SIMPLE_TAG_3))
                .then()
                .body("data.attributes.value", Matchers.nullValue())
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspacetag\",\"id\":\"" + bindingId
                        + "\",\"attributes\":{\"value\":\"filled\"}}}")
                .when()
                .patch(workspaceTagPath(SIMPLE_TAG_3) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspacetag\",\"id\":\"" + bindingId
                        + "\",\"attributes\":{\"value\":null}}}")
                .when()
                .patch(workspaceTagPath(SIMPLE_TAG_3) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get(workspaceTagPath(SIMPLE_TAG_3) + "/" + bindingId)
                .then()
                .body("data.attributes.value", Matchers.nullValue())
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete(workspaceTagPath(SIMPLE_TAG_3) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/" + ORGANIZATION_ID + "/tag/" + tagId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    private String createTag(String name) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type",
                        "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"tag\",\"attributes\":{\"name\":\"" + name + "\"}}}")
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/tag")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");
    }

    private static String workspaceTagPath(String workspaceId) {
        return "/api/v1/organization/" + ORGANIZATION_ID + "/workspace/" + workspaceId + "/workspaceTag";
    }

    private static String binding(String tagId, String value) {
        return "{\"data\":{\"type\":\"workspacetag\",\"attributes\":{\"tagId\":\"" + tagId + "\",\"value\":\"" + value
                + "\"}}}";
    }

    private static String pair(String tagId, String value) {
        return "workspaceTag.tagId==\"" + tagId + "\";workspaceTag.value==\"" + value + "\"";
    }

    private Response search(String filter) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/json")
                .body(Map.of("query", WORKSPACE_QUERY, "variables",
                        Map.of("organizationIds", List.of(ORGANIZATION_ID), "filter", filter)))
                .when()
                .post("/graphql/api/v1");
    }

    private static List<String> names(Response response) {
        assertThat(response.jsonPath().getList("errors")).as(response.asPrettyString()).isNull();
        return response.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class);
    }

    private static List<String> ids(Response response) {
        assertThat(response.jsonPath().getList("errors")).as(response.asPrettyString()).isNull();
        return response.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.id", String.class);
    }
}
