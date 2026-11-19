package io.terrakube.api;

import io.terrakube.api.repository.CollectionRepository;
import io.terrakube.api.repository.ItemRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * A variable collection applies to a workspace when it is global, when it references the workspace or when it
 * references the workspace's project.
 *
 * Deliberately NOT @Transactional: ExecutorService.loadDefault reads the collections after ScheduleJob has
 * released its transaction (see ScheduleJob's class comment), so the repository call gets no ambient
 * transaction here either, and reading the items of a collection that was not fetched with them would throw
 * LazyInitializationException exactly as it would in production.
 */
class CollectionScopeTests extends ServerApplicationTests {

    private static final String ORG_A_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String ORG_B_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final String JSON_API_CONTENT_TYPE = "application/vnd.api+json";

    @Autowired
    CollectionRepository collectionRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    ReferenceRepository referenceRepository;

    private Organization orgA;
    private Organization orgB;
    private final List<Team> teams = new ArrayList<>();
    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<Project> projects = new ArrayList<>();
    private final List<Collection> collections = new ArrayList<>();

    @BeforeEach
    void setUpOrganizations() {
        orgA = organizationRepository.findById(UUID.fromString(ORG_A_ID)).orElseThrow();
        orgB = organizationRepository.findById(UUID.fromString(ORG_B_ID)).orElseThrow();
    }

    @AfterEach
    void cleanup() {
        for (Collection collection : collections) {
            referenceRepository.findAll().stream()
                    .filter(reference -> reference.getCollection() != null
                            && collection.getId().equals(reference.getCollection().getId()))
                    .forEach(referenceRepository::delete);
            itemRepository.findAll().stream()
                    .filter(item -> item.getCollection() != null
                            && collection.getId().equals(item.getCollection().getId()))
                    .forEach(itemRepository::delete);
            collectionRepository.deleteById(collection.getId());
        }
        collections.clear();
        for (Workspace workspace : workspaces) {
            Workspace current = workspaceRepository.findById(workspace.getId()).orElseThrow();
            current.setDeleted(true);
            current.setProject(null);
            workspaceRepository.save(current);
        }
        workspaces.clear();
        projects.forEach(project -> projectRepository.findById(project.getId()).ifPresent(projectRepository::delete));
        projects.clear();
        teamRepository.deleteAll(teams);
        teams.clear();
    }

    @Test
    void aWorkspaceGetsItsGlobalProjectAndDirectCollectionsOnlyOnceEach() {
        Project project = project(orgA);
        Project otherProject = project(orgA);
        Workspace workspace = workspace(orgA, project);
        Workspace sibling = workspace(orgA, project);

        Collection global = collection(orgA, "global", true);
        item(global, "GLOBAL_KEY");
        Collection throughProject = collection(orgA, "through-project", false);
        item(throughProject, "PROJECT_KEY");
        projectReference(throughProject, project);
        Collection direct = collection(orgA, "direct", false);
        item(direct, "DIRECT_KEY");
        workspaceReference(direct, workspace);
        // Applies through every path at once and must still come back a single time.
        Collection everywhere = collection(orgA, "everywhere", true);
        item(everywhere, "FIRST_KEY");
        item(everywhere, "SECOND_KEY");
        workspaceReference(everywhere, workspace);
        projectReference(everywhere, project);
        // A collection without variables is still applicable.
        Collection empty = collection(orgA, "empty", false);
        workspaceReference(empty, workspace);

        Collection otherProjectOnly = collection(orgA, "other-project", false);
        projectReference(otherProjectOnly, otherProject);
        Collection siblingOnly = collection(orgA, "sibling", false);
        workspaceReference(siblingOnly, sibling);
        Collection otherOrganizationGlobal = collection(orgB, "other-organization-global", true);

        List<Collection> applicable = ownCollections(
                collectionRepository.findApplicableToWorkspaceWithItems(orgA, workspace, project));

        assertThat(applicable).extracting(Collection::getName)
                .containsExactlyInAnyOrder(global.getName(), throughProject.getName(), direct.getName(),
                        everywhere.getName(), empty.getName())
                .doesNotContain(otherProjectOnly.getName(), siblingOnly.getName(), otherOrganizationGlobal.getName());
        Collection loadedEverywhere = applicable.stream()
                .filter(collection -> collection.getId().equals(everywhere.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(loadedEverywhere.getItem()).extracting(Item::getKey)
                .containsExactlyInAnyOrder("FIRST_KEY", "SECOND_KEY");
        Collection loadedEmpty = applicable.stream()
                .filter(collection -> collection.getId().equals(empty.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(loadedEmpty.getItem()).isEmpty();
    }

    @Test
    void aWorkspaceOutsideAnyProjectGetsGlobalAndDirectCollections() {
        Project project = project(orgA);
        Workspace workspace = workspace(orgA, null);

        Collection global = collection(orgA, "global", true);
        Collection direct = collection(orgA, "direct", false);
        workspaceReference(direct, workspace);
        Collection throughProject = collection(orgA, "through-project", false);
        projectReference(throughProject, project);

        List<Collection> applicable = ownCollections(
                collectionRepository.findApplicableToWorkspaceWithItems(orgA, workspace, null));

        assertThat(applicable).extracting(Collection::getName)
                .containsExactlyInAnyOrder(global.getName(), direct.getName());
    }

    @Test
    void aCollectionManagerCanMakeACollectionGlobalAndApplyItToAProject() {
        String token = generatePAT(collectionManager(orgA).getName());
        Project project = project(orgA);
        Collection collection = collection(orgA, "managed", false);

        given()
                .headers("Authorization", "Bearer " + token)
                .contentType(JSON_API_CONTENT_TYPE)
                .body("""
                        {"data": {"type": "collection", "id": "%s", "attributes": {"global": true}}}
                        """.formatted(collection.getId()))
                .when()
                .patch("/api/v1/organization/" + ORG_A_ID + "/collection/" + collection.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
        assertThat(collectionRepository.findById(collection.getId()).orElseThrow().isGlobal()).isTrue();

        String referenceId = given()
                .headers("Authorization", "Bearer " + token)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(referencePayload("project", project.getId()))
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/collection/" + collection.getId() + "/reference")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.relationships.project.data.id", equalTo(project.getId().toString()))
                .extract()
                .path("data.id");

        Reference reference = referenceRepository.findById(UUID.fromString(referenceId)).orElseThrow();
        assertThat(reference.getProject().getId()).isEqualTo(project.getId());
        assertThat(reference.getWorkspace()).isNull();
    }

    @Test
    void aCollectionCannotBeAppliedToAProjectOfAnotherOrganization() {
        // Admin in both organizations, so the project is visible and only the organization check can refuse it.
        String token = generatePAT(collectionManager(orgA).getName(), collectionManager(orgB).getName());
        Project foreignProject = project(orgB);
        Collection collection = collection(orgA, "cross-organization", false);

        given()
                .headers("Authorization", "Bearer " + token)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(referencePayload("project", foreignProject.getId()))
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/collection/" + collection.getId() + "/reference")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        assertThat(referenceRepository.findAll()).noneMatch(reference -> reference.getCollection() != null
                && collection.getId().equals(reference.getCollection().getId()));
    }

    @Test
    void deletingAProjectRemovesItsCollectionReferencesButKeepsTheCollection() {
        Project project = project(orgA);
        Collection collection = collection(orgA, "project-scoped", false);
        Reference reference = projectReference(collection, project);

        projectRepository.deleteById(project.getId());

        assertThat(referenceRepository.findById(reference.getId())).isEmpty();
        assertThat(collectionRepository.findById(collection.getId())).isPresent();
    }

    private List<Collection> ownCollections(List<Collection> result) {
        Set<UUID> ownIds = collections.stream().map(Collection::getId).collect(java.util.stream.Collectors.toSet());
        return result.stream().filter(collection -> ownIds.contains(collection.getId())).toList();
    }

    private static String referencePayload(String type, UUID id) {
        return """
                {
                  "data": {
                    "type": "reference",
                    "attributes": {"description": "Collection scope test"},
                    "relationships": {"%s": {"data": {"type": "%s", "id": "%s"}}}
                  }
                }
                """.formatted(type, type, id);
    }

    // Managing workspaces is what lets an organization team see the projects it applies a collection to.
    private Team collectionManager(Organization organization) {
        Team team = new Team();
        team.setName("COLLECTION_SCOPE_" + UUID.randomUUID());
        team.setOrganization(organization);
        team.setRole("custom");
        team.setManageCollection(true);
        team.setManageState(false);
        team.setManageJob(false);
        team.setManageWorkspace(true);
        team.setManageModule(false);
        team.setManageProvider(false);
        team.setManageVcs(false);
        team.setManageTemplate(false);
        team.setManagePolicies(false);
        team.setPlanJob(false);
        team.setApproveJob(false);
        team.setDestroyJob(false);
        team = teamRepository.saveAndFlush(team);
        teams.add(team);
        return team;
    }

    private Project project(Organization organization) {
        Project project = new Project();
        project.setName("collection-scope-" + UUID.randomUUID());
        project.setOrganization(organization);
        project = projectRepository.saveAndFlush(project);
        projects.add(project);
        return project;
    }

    private Workspace workspace(Organization organization, Project project) {
        Workspace workspace = new Workspace();
        workspace.setName("collection-scope-" + UUID.randomUUID());
        workspace.setSource("https://github.com/terrakube-io/terrakube.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.13.1");
        workspace.setOrganization(organization);
        workspace.setProject(project);
        workspace = workspaceRepository.saveAndFlush(workspace);
        workspaces.add(workspace);
        return workspace;
    }

    private Collection collection(Organization organization, String name, boolean global) {
        Collection collection = new Collection();
        collection.setName(name + "-" + UUID.randomUUID());
        collection.setPriority(10);
        collection.setGlobal(global);
        collection.setOrganization(organization);
        collection = collectionRepository.saveAndFlush(collection);
        collections.add(collection);
        return collection;
    }

    private void item(Collection collection, String key) {
        Item item = new Item();
        item.setKey(key);
        item.setValue("value");
        item.setCategory(Category.ENV);
        item.setCollection(collection);
        itemRepository.saveAndFlush(item);
    }

    private Reference workspaceReference(Collection collection, Workspace workspace) {
        Reference reference = new Reference();
        reference.setCollection(collection);
        reference.setWorkspace(workspace);
        return referenceRepository.saveAndFlush(reference);
    }

    private Reference projectReference(Collection collection, Project project) {
        Reference reference = new Reference();
        reference.setCollection(collection);
        reference.setProject(project);
        return referenceRepository.saveAndFlush(reference);
    }
}
