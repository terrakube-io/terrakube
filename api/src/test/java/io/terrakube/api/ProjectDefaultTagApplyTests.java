package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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

// Applying a project's default tags to workspaces that are already in it, with a dry run first.
class ProjectDefaultTagApplyTests extends ServerApplicationTests {

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
    private Workspace untagged;
    private Workspace tagged;
    private final List<String> workspaceIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();

        project = new Project();
        project.setName("apply-" + UUID.randomUUID());
        project.setOrganization(organization);
        project = projectRepository.save(project);

        tag = new Tag();
        tag.setName("a-" + UUID.randomUUID().toString().substring(0, 8));
        tag.setOrganization(organization);
        tag = tagRepository.save(tag);

        ProjectTag projectTag = new ProjectTag();
        projectTag.setProject(project);
        projectTag.setTagId(tag.getId().toString());
        projectTag.setValue("default");
        projectTagRepository.save(projectTag);

        untagged = workspace(organization, "untagged");
        tagged = workspace(organization, "tagged");
        WorkspaceTag own = new WorkspaceTag();
        own.setWorkspace(tagged);
        own.setTagId(tag.getId().toString());
        own.setValue("own");
        workspaceTagRepository.save(own);
    }

    @AfterEach
    void cleanUp() {
        for (String id : workspaceIds) {
            jdbcTemplate.update("delete from workspacetag where workspace_id = ?", id);
            jdbcTemplate.update("delete from workspace where id = ?", id);
        }
        jdbcTemplate.update("delete from project_tag where project_id = ?", project.getId().toString());
        jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
        jdbcTemplate.update("delete from tag where id = ?", tag.getId().toString());
    }

    @Test
    void dryRunReportsTheImpactWithoutChangingAnything() {
        apply("TERRAKUBE_DEVELOPERS", true)
                .statusCode(HttpStatus.OK.value())
                .body("workspaces", is(2))
                .body("workspacesChanged", is(1))
                .body("tagsAdded", is(1));

        assertThat(workspaceTagRepository.findByWorkspace(untagged)).isEmpty();
    }

    @Test
    void applyingAddsOnlyMissingTagsKeepsOwnValuesAndIsRepeatable() {
        apply("TERRAKUBE_DEVELOPERS", false).statusCode(HttpStatus.OK.value()).body("tagsAdded", is(1));

        assertThat(workspaceTagRepository.findByWorkspace(untagged)).extracting(WorkspaceTag::getValue).containsExactly("default");
        assertThat(workspaceTagRepository.findByWorkspace(tagged)).extracting(WorkspaceTag::getValue).containsExactly("own");

        apply("TERRAKUBE_DEVELOPERS", false).statusCode(HttpStatus.OK.value()).body("tagsAdded", is(0)).body("workspacesChanged", is(0));
    }

    @Test
    void aCallerWhoCannotManageTheProjectGetsNotFound() {
        apply("NOT_A_MEMBER", false).statusCode(HttpStatus.NOT_FOUND.value());

        assertThat(workspaceTagRepository.findByWorkspace(untagged)).isEmpty();
    }

    private io.restassured.response.ValidatableResponse apply(String team, boolean dryRun) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(team))
                .when()
                .post("/access-token/v1/projects/organization/" + ORG + "/project/" + project.getId()
                        + "/default-tags/apply?dryRun=" + dryRun)
                .then();
    }

    private Workspace workspace(Organization organization, String suffix) {
        Workspace workspace = new Workspace();
        workspace.setName("apply-" + suffix + "-" + UUID.randomUUID());
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organization);
        workspace.setProject(project);
        workspace = workspaceRepository.save(workspace);
        workspaceIds.add(workspace.getId().toString());
        return workspace;
    }
}
