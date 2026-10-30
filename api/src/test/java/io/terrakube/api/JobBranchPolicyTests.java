package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A workspace can restrict apply to its configured branch: a job that can change infrastructure may not use
 * another branch or name a commit, while a plan-only job runs anywhere. Every case goes through the real Elide
 * checks and hooks.
 */
class JobBranchPolicyTests extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String PLAN = "42201234-a5e2-4c62-b2fc-9729ca6b4515";
    private static final String PLAN_AND_APPLY = "2db36f7c-f549-4341-a789-315d47eb061d";
    private static final String DESTROY = "d944e19d-bc3e-41b0-bdf9-1881e1a459c4";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String JSON_API = "application/vnd.api+json";

    private Organization organization;
    private Workspace workspace;
    private String manager;
    private final List<Team> teams = new ArrayList<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        wireMockServer.resetAll();
        stubFor(post(urlPathEqualTo("/api/v1/terraform-rs"))
                .willReturn(aResponse().withStatus(HttpStatus.ACCEPTED.value()).withBody("")));

        organization = organizationRepository.getReferenceById(UUID.fromString(ORG_ID));
        workspace = newWorkspace(true);
        // An organization team with the admin role, so these cases do not depend on the seeded identities.
        manager = team("admin").getName();
    }

    @AfterEach
    void cleanup() {
        jobRepository.findAll().stream()
                .filter(job -> job.getWorkspace() != null && workspace.getId().equals(job.getWorkspace().getId()))
                .forEach(job -> {
                    stepRepository.deleteAll(stepRepository.findByJobId(job.getId()));
                    jobRepository.delete(job);
                });
        Workspace current = workspaceRepository.findById(workspace.getId()).orElseThrow();
        current.setDeleted(true);
        workspaceRepository.save(current);
        teamRepository.deleteAll(teams);
        teams.clear();
    }

    // Creating a job

    @Test
    void aTemplateThatAppliesIsRefusedOnAnotherBranch() {
        long before = jobRepository.count();

        var response = createJob(PLAN_AND_APPLY, "feature/x", null);

        assertEquals(HttpStatus.FORBIDDEN.value(), response.statusCode(), response.asString());
        assertTrue(response.asString().contains("only allows plan runs"), response.asString());
        assertTrue(response.asString().contains("other than main"), response.asString());
        assertEquals(before, jobRepository.count(), "a refused job must not be saved");
    }

    @Test
    void aDestroyTemplateIsRefusedOnAnotherBranch() {
        assertEquals(HttpStatus.FORBIDDEN.value(), createJob(DESTROY, "feature/x", null).statusCode());
    }

    @Test
    void aPlanOnlyTemplateRunsOnAnotherBranch() {
        assertEquals(HttpStatus.CREATED.value(), createJob(PLAN, "feature/x", null).statusCode());
    }

    @Test
    void aTemplateThatAppliesRunsOnTheConfiguredBranch() {
        assertEquals(HttpStatus.CREATED.value(), createJob(PLAN_AND_APPLY, "main", null).statusCode());
        assertEquals(HttpStatus.CREATED.value(), createJob(PLAN_AND_APPLY, "refs/heads/main", null).statusCode());
        assertEquals(HttpStatus.CREATED.value(), createJob(PLAN_AND_APPLY, null, null).statusCode());
    }

    @Test
    void aCommitIdIsRefusedForATemplateThatAppliesEvenOnTheConfiguredBranch() {
        // The executor fetches the commit by id, so it could come from another branch.
        long before = jobRepository.count();

        assertEquals(HttpStatus.FORBIDDEN.value(), createJob(PLAN_AND_APPLY, "main", COMMIT).statusCode());
        assertEquals(HttpStatus.FORBIDDEN.value(), createJob(PLAN_AND_APPLY, null, COMMIT).statusCode());

        assertEquals(before, jobRepository.count());
    }

    @Test
    void aCommitIdIsAllowedForAPlanOnlyTemplate() {
        assertEquals(HttpStatus.CREATED.value(), createJob(PLAN, "feature/x", COMMIT).statusCode());
    }

    @Test
    void withTheSettingOffNothingChanges() {
        setRestriction(false);

        assertEquals(HttpStatus.CREATED.value(), createJob(PLAN_AND_APPLY, "feature/x", null).statusCode());
        assertEquals(HttpStatus.CREATED.value(), createJob(DESTROY, "feature/x", COMMIT).statusCode());
    }

    @Test
    void aNewWorkspaceDoesNotRestrictApply() {
        assertEquals(false, newWorkspace(false).isRestrictApplyToConfiguredBranch());
    }

    // Changing the branch or the commit of a job that already exists

    @Test
    void theBranchAndTheCommitOfAJobCannotBeChangedWhileTheWorkspaceRestrictsApply() {
        int jobId = jobId(createJob(PLAN, "feature/x", null));

        assertEquals(HttpStatus.FORBIDDEN.value(), patchJob(jobId, "overrideBranch", "main").statusCode());
        assertEquals(HttpStatus.FORBIDDEN.value(), patchJob(jobId, "commitId", COMMIT).statusCode());

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertEquals("feature/x", job.getOverrideBranch());
        assertEquals(null, job.getCommitId());
    }

    @Test
    void theBranchAndTheCommitOfAJobCanBeChangedWhenTheWorkspaceDoesNotRestrictApply() {
        setRestriction(false);
        int jobId = jobId(createJob(PLAN, "feature/x", null));

        assertEquals(HttpStatus.NO_CONTENT.value(), patchJob(jobId, "overrideBranch", "main").statusCode());
        assertEquals(HttpStatus.NO_CONTENT.value(), patchJob(jobId, "commitId", COMMIT).statusCode());
    }

    @Test
    void aTeamThatCannotManageJobsStillCannotChangeThem() {
        setRestriction(false);
        int jobId = jobId(createJob(PLAN, "feature/x", null));
        String reader = team("read").getName();

        assertEquals(HttpStatus.FORBIDDEN.value(), patchJob(reader, jobId, "overrideBranch", "main").statusCode());
    }

    // Changing the setting

    @Test
    void anOrganizationManagerCanChangeTheSetting() {
        assertEquals(HttpStatus.NO_CONTENT.value(), patchWorkspace(token(manager), false).statusCode());
        assertEquals(false, workspaceRepository.findById(workspace.getId()).orElseThrow()
                .isRestrictApplyToConfiguredBranch());

        assertEquals(HttpStatus.NO_CONTENT.value(), patchWorkspace(token(manager), true).statusCode());
        assertEquals(true, workspaceRepository.findById(workspace.getId()).orElseThrow()
                .isRestrictApplyToConfiguredBranch());
    }

    @Test
    void aManagerLimitedToTheWorkspaceCannotSwitchTheGuardOff() {
        String name = team("read").getName();
        bindToWorkspace(name);

        assertEquals(HttpStatus.FORBIDDEN.value(), patchWorkspace(token(name), false).statusCode());
        assertEquals(true, workspaceRepository.findById(workspace.getId()).orElseThrow()
                .isRestrictApplyToConfiguredBranch());
        // The rest of the workspace stays editable for that manager.
        assertEquals(HttpStatus.NO_CONTENT.value(), given()
                .headers("Authorization", token(name), "Content-Type", JSON_API)
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"" + workspace.getId()
                        + "\",\"attributes\":{\"description\":\"edited by a limited manager\"}}}")
                .patch(workspaceUrl()).then().extract().statusCode());
    }

    // Helpers

    private Workspace newWorkspace(boolean restricted) {
        Workspace created = new Workspace();
        created.setName(UUID.randomUUID().toString());
        created.setSource("https://github.com/AzBuilder/terrakube-docker-compose.git");
        created.setBranch("main");
        created.setTerraformVersion("1.2.5");
        created.setOrganization(organization);
        created.setRestrictApplyToConfiguredBranch(restricted);
        return workspaceRepository.save(created);
    }

    private void setRestriction(boolean restricted) {
        Workspace current = workspaceRepository.findById(workspace.getId()).orElseThrow();
        current.setRestrictApplyToConfiguredBranch(restricted);
        workspace = workspaceRepository.save(current);
    }

    private Team team(String role) {
        Team team = new Team();
        team.setName("BRANCH_POLICY_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        team.setOrganization(organization);
        team.setRole(role);
        team.setPlanJob(false);
        team.setApproveJob(false);
        team.setManageJob(false);
        team.setManageWorkspace(false);
        team.setManageState(false);
        team.setManageCollection(false);
        team.setManageModule(false);
        team.setManageProvider(false);
        team.setManageVcs(false);
        team.setManageTemplate(false);
        team.setManagePolicies(false);
        team = teamRepository.save(team);
        teams.add(team);
        return team;
    }

    private void bindToWorkspace(String team) {
        given()
                .headers("Authorization", token("TERRAKUBE_ADMIN"), "Content-Type", JSON_API)
                .body("{\"data\":{\"type\":\"access\",\"attributes\":{\"name\":\"" + team + "\",\"role\":\"custom\","
                        + "\"manageWorkspace\":true,\"manageJob\":false,\"manageState\":false,"
                        + "\"planJob\":false,\"approveJob\":false}}}")
                .post(workspaceUrl() + "/access")
                .then().statusCode(HttpStatus.CREATED.value());
    }

    private String token(String team) {
        return "Bearer " + generatePAT(team);
    }

    private String workspaceUrl() {
        return "/api/v1/organization/" + ORG_ID + "/workspace/" + workspace.getId();
    }

    private io.restassured.response.Response patchWorkspace(String authorization, boolean restricted) {
        return given()
                .headers("Authorization", authorization, "Content-Type", JSON_API)
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"" + workspace.getId()
                        + "\",\"attributes\":{\"restrictApplyToConfiguredBranch\":" + restricted + "}}}")
                .patch(workspaceUrl());
    }

    private io.restassured.response.Response createJob(String template, String branch, String commitId) {
        String attributes = "\"templateReference\":\"" + template + "\""
                + (branch == null ? "" : ",\"overrideBranch\":\"" + branch + "\"")
                + (commitId == null ? "" : ",\"commitId\":\"" + commitId + "\"");
        return given()
                .headers("Authorization", token(manager), "Content-Type", JSON_API)
                .body("{\"data\":{\"type\":\"job\",\"attributes\":{" + attributes + "},"
                        + "\"relationships\":{\"workspace\":{\"data\":{\"type\":\"workspace\",\"id\":\""
                        + workspace.getId() + "\"}}}}}")
                .post("/api/v1/organization/" + ORG_ID + "/job");
    }

    private int jobId(io.restassured.response.Response created) {
        assertEquals(HttpStatus.CREATED.value(), created.statusCode(), created.asString());
        return Integer.parseInt(created.path("data.id"));
    }

    private io.restassured.response.Response patchJob(int jobId, String attribute, String value) {
        return patchJob(manager, jobId, attribute, value);
    }

    private io.restassured.response.Response patchJob(String team, int jobId, String attribute, String value) {
        return given()
                .headers("Authorization", token(team), "Content-Type", JSON_API)
                .body("{\"data\":{\"type\":\"job\",\"id\":\"" + jobId + "\",\"attributes\":{\"" + attribute
                        + "\":\"" + value + "\"}}}")
                .patch("/api/v1/organization/" + ORG_ID + "/job/" + jobId);
    }
}
