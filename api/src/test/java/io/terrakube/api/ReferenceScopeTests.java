package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import io.terrakube.api.repository.CollectionRepository;
import io.terrakube.api.repository.ItemRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.ReferenceScope;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.parameters.Category;

// Collections attached to a project or to the whole organization, through the same endpoint workspaces use.
class ReferenceScopeTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private ReferenceRepository referenceRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Organization organization;
    private Project project;
    private Collection collection;

    @BeforeEach
    void seed() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();

        project = new Project();
        project.setName("scope-" + UUID.randomUUID());
        project.setOrganization(organization);
        project = projectRepository.save(project);

        collection = new Collection();
        collection.setName("scope-" + UUID.randomUUID());
        collection.setPriority(1);
        collection.setOrganization(organization);
        collection = collectionRepository.saveAndFlush(collection);
        Item item = new Item();
        item.setKey("SCOPED_KEY");
        item.setValue("v");
        item.setCategory(Category.ENV);
        item.setCollection(collection);
        itemRepository.saveAndFlush(item);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from reference where collection_id = ?", collection.getId().toString());
        jdbcTemplate.update("delete from item where collection_id = ?", collection.getId().toString());
        jdbcTemplate.update("delete from collection where id = ?", collection.getId().toString());
        jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
    }

    @Test
    void aProjectScopedReferenceCanBeCreatedAndIsFoundForTheProject() {
        createReference("PROJECT", project.getId().toString(), HttpStatus.CREATED.value());

        List<Reference> found = referenceRepository.findByProjectWithCollectionItems(project, ReferenceScope.PROJECT);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getCollection().getItem()).extracting(Item::getKey).containsExactly("SCOPED_KEY");
    }

    @Test
    void anOrganizationScopedReferenceNeedsNoTarget() {
        createReference("ORGANIZATION", null, HttpStatus.CREATED.value());

        assertThat(referenceRepository.findByOrganizationWithCollectionItems(organization, ReferenceScope.ORGANIZATION))
                .extracting(r -> r.getCollection().getId()).contains(collection.getId());
    }

    @Test
    void attachingTheSameCollectionToTheSameProjectTwiceIsAConflict() {
        createReference("PROJECT", project.getId().toString(), HttpStatus.CREATED.value());

        createReference("PROJECT", project.getId().toString(), HttpStatus.CONFLICT.value());

        assertThat(referenceRepository.findByProjectWithCollectionItems(project, ReferenceScope.PROJECT)).hasSize(1);
    }

    @Test
    void attachingTheSameCollectionToTheOrganizationTwiceIsAConflict() {
        createReference("ORGANIZATION", null, HttpStatus.CREATED.value());

        createReference("ORGANIZATION", null, HttpStatus.CONFLICT.value());
    }

    @Test
    void aProjectScopedReferenceWithoutAProjectIsRejected() {
        createReference("PROJECT", null, HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void anOrganizationScopedReferenceWithAProjectIsRejected() {
        createReference("ORGANIZATION", project.getId().toString(), HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void aWorkspaceReferenceWithoutAWorkspaceIsRejected() {
        createReference("WORKSPACE", null, HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void theProjectVariablesTabQueryFindsTheReferenceThatAttachesACollectionToTheProject() {
        createReference("PROJECT", project.getId().toString(), HttpStatus.CREATED.value());
        String filter = "scope=='PROJECT';project.id=='" + project.getId() + "'";
        String query = "{ organization(ids: [\"" + ORG + "\"]) { edges { node { collection(filter: \"id==" + collection.getId()
                + "\") { edges { node { id item { edges { node { id } } } reference(filter: \"" + filter.replace("\"", "\\\"")
                + "\") { edges { node { id } } } } } } } } } }";

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/json")
                .body(java.util.Map.of("query", query))
                .when().post("/graphql/api/v1")
                .then().log().ifValidationFails()
                .statusCode(HttpStatus.OK.value())
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("errors")))
                .body("data.organization.edges[0].node.collection.edges[0].node.reference.edges.size()", org.hamcrest.Matchers.is(1))
                .body("data.organization.edges[0].node.collection.edges[0].node.item.edges.size()", org.hamcrest.Matchers.is(1));
    }

    private void createReference(String scope, String projectId, int expectedStatus) {
        String relationships = projectId == null ? ""
                : ",\"project\":{\"data\":{\"type\":\"project\",\"id\":\"" + projectId + "\"}}";
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"reference\",\"attributes\":{\"description\":\"scope test\",\"scope\":\"" + scope
                        + "\"},\"relationships\":{\"collection\":{\"data\":{\"type\":\"collection\",\"id\":\""
                        + collection.getId() + "\"}}" + relationships + "}}}")
                .when()
                .post("/api/v1/organization/" + ORG + "/collection/" + collection.getId() + "/reference")
                .then()
                .log().ifValidationFails()
                .statusCode(expectedStatus);
    }
}
