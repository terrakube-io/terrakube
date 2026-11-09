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

import io.terrakube.api.repository.ProjectTagRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.tag.ProjectTag;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;

// A project's default tags are copied onto workspaces created in it or moved into it.
class ProjectDefaultTagTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private ProjectTagRepository projectTagRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceTagRepository workspaceTagRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project project;
    private Tag tag;
    private final String workspaceName = "default-tag-" + UUID.randomUUID();

    @BeforeEach
    void seed() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();

        project = new Project();
        project.setName("default-tag-" + UUID.randomUUID());
        project.setOrganization(organization);
        project = projectRepository.save(project);

        tag = new Tag();
        tag.setName("t-" + UUID.randomUUID().toString().substring(0, 8));
        tag.setOrganization(organization);
        tag = tagRepository.save(tag);

        ProjectTag projectTag = new ProjectTag();
        projectTag.setProject(project);
        projectTag.setTagId(tag.getId().toString());
        projectTag.setValue("from-project");
        projectTagRepository.save(projectTag);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from workspacetag where workspace_id in (select id from workspace where name = ?)", workspaceName);
        jdbcTemplate.update("delete from workspace where name = ?", workspaceName);
        jdbcTemplate.update("delete from project_tag where project_id = ?", project.getId().toString());
        jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
        jdbcTemplate.update("delete from tag where id = ?", tag.getId().toString());
    }

    @Test
    void aWorkspaceCreatedInTheProjectGetsItsDefaultTags() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"attributes\":{\"name\":\"" + workspaceName
                        + "\",\"source\":\"empty\",\"branch\":\"remote-content\",\"terraformVersion\":\"1.14.0\"},"
                        + "\"relationships\":{\"project\":{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\"}}}}}")
                .when()
                .post("/api/v1/organization/" + ORG + "/workspace")
                .then()
                .statusCode(HttpStatus.CREATED.value());

        assertThat(tagsOf(workspaceName)).extracting(WorkspaceTag::getTagId).containsExactly(tag.getId().toString());
        assertThat(tagsOf(workspaceName)).extracting(WorkspaceTag::getValue).containsExactly("from-project");
    }

    @Test
    void movingAWorkspaceIntoTheProjectCopiesTheTagsOnceAndKeepsExistingValues() {
        Workspace workspace = new Workspace();
        workspace.setName(workspaceName);
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        workspace = workspaceRepository.save(workspace);
        WorkspaceTag own = new WorkspaceTag();
        own.setWorkspace(workspace);
        own.setTagId(tag.getId().toString());
        own.setValue("own-value");
        workspaceTagRepository.save(own);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\"}}")
                .when()
                .patch("/api/v1/organization/" + ORG + "/workspace/" + workspace.getId() + "/relationships/project")
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        List<WorkspaceTag> tags = tagsOf(workspaceName);
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).getValue()).isEqualTo("own-value");
    }

    @Test
    void defaultTagsCanBeListedAndManagedThroughTheApiPathTheUiUses() {
        String base = "/api/v1/organization/" + ORG + "/project/" + project.getId() + "/projectTag";

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().get(base)
                .then().statusCode(HttpStatus.OK.value())
                .body("data.size()", org.hamcrest.Matchers.is(1))
                .body("data[0].attributes.tagId", org.hamcrest.Matchers.is(tag.getId().toString()))
                .body("data[0].attributes.value", org.hamcrest.Matchers.is("from-project"));

        Tag other = new Tag();
        other.setName("o-" + UUID.randomUUID().toString().substring(0, 8));
        other.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        other = tagRepository.save(other);
        try {
            String id = given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                    .body("{\"data\":{\"type\":\"project_tag\",\"attributes\":{\"tagId\":\"" + other.getId() + "\"}}}")
                    .when().post(base)
                    .then().statusCode(HttpStatus.CREATED.value())
                    .extract().path("data.id");

            given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                    .when().delete(base + "/" + id)
                    .then().statusCode(HttpStatus.NO_CONTENT.value());
        } finally {
            jdbcTemplate.update("delete from project_tag where tag_id = ?", other.getId().toString());
            jdbcTemplate.update("delete from tag where id = ?", other.getId().toString());
        }
    }

    @Test
    void aCallerWithoutAnyAccessToTheProjectCannotChangeItsDefaultTags() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("NOT_A_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project_tag\",\"attributes\":{\"tagId\":\"" + tag.getId() + "\"}}}")
                .when().post("/api/v1/organization/" + ORG + "/project/" + project.getId() + "/projectTag")
                .then().statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(HttpStatus.FORBIDDEN.value()), org.hamcrest.Matchers.is(HttpStatus.NOT_FOUND.value())));
    }

    private List<WorkspaceTag> tagsOf(String name) {
        Workspace workspace = workspaceRepository.findAllByOrganizationNameAndName("terrakube", name).stream().findFirst()
                .orElseGet(() -> workspaceRepository.findAll().stream().filter(w -> name.equals(w.getName())).findFirst().orElseThrow());
        return workspaceTagRepository.findByWorkspace(workspace);
    }
}
