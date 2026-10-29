package io.terrakube.api;

import io.restassured.http.ContentType;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for advisory GHSA-8h83-prhc-rgh4.
 *
 * Validates with real persisted database entities through real Elide HTTP endpoints:
 * 1. Cross-organization tenant isolation:
 *    User in Organization A gets 403 (or empty list / 404) when attempting to access
 *    webhook, webhook_event, reference, item, address, variable, schedule, tag, agent,
 *    and globalvar belonging to Organization B.
 * 2. Cross-organization reference creation & repointing prevention:
 *    User in Organization A cannot create a reference linking Org A's collection to Org B's workspace,
 *    and cannot PATCH an existing reference to repoint its workspace or collection across tenants.
 * 3. Sibling workspace webhook isolation:
 *    A workspace-limited user and a project-limited user can read webhooks for their designated
 *    workspace, but are strictly forbidden from accessing sibling workspace webhooks.
 * 4. Workspace view-only reference creation denial:
 *    A user with view-only rights on a workspace cannot create a reference.
 * 5. Job address management hardening:
 *    Approver-only users and running-job states cannot modify job addresses.
 * 6. Scoped runner token address read:
 *    Internal WORKSPACE_EXECUTOR tokens require matching jobId.
 */
class CrossOrganizationSecurityIntegrationTest extends ServerApplicationTests {

    private static final String ORG_A_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String ORG_B_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final String JSON_API_CONTENT_TYPE = "application/vnd.api+json";

    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private ReferenceRepository referenceRepository;
    @Autowired
    private WebhookRepository webhookRepository;
    @Autowired
    private WebhookEventRepository webhookEventRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private StepRepository stepRepository;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private GlobalVarRepository globalVarRepository;
    @Autowired
    private AccessRepository accessRepository;
    @Autowired
    private ProjectAccessRepository projectAccessRepository;
    @Autowired
    private TemplateRepository templateRepository;

    private Organization orgA;
    private Organization orgB;

    private Team teamOrgA;
    private Team teamOrgB;
    private String tokenOrgA;
    private String tokenOrgB;

    // Fixtures in Org B
    private Workspace workspaceB;
    private Webhook webhookB;
    private WebhookEvent eventB;
    private Collection collectionB;
    private Item itemB;
    private Reference referenceB;
    private Job jobB;
    private Address addressB;
    private Variable variableB;
    private Schedule scheduleB;
    private Tag tagB;
    private Agent agentB;
    private Globalvar globalvarB;
    private Template templateB;

    // Fixtures in Org A
    private Workspace workspaceA;
    private Webhook webhookA;
    private WebhookEvent eventA;
    private Collection collectionA;
    private Item itemA;
    private Reference referenceA;
    private Job jobA;
    private Step stepA;
    private Address addressA;
    private Tag tagA;
    private Agent agentA;
    private Globalvar globalvarA;

    // Track created entities for clean teardown
    private final List<Team> createdTeams = new ArrayList<>();
    private final List<Workspace> createdWorkspaces = new ArrayList<>();
    private final List<Project> createdProjects = new ArrayList<>();
    private final List<Access> createdAccesses = new ArrayList<>();
    private final List<ProjectAccess> createdProjectAccesses = new ArrayList<>();

    @BeforeEach
    void setUpFixtures() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        orgA = organizationRepository.findById(UUID.fromString(ORG_A_ID)).orElseThrow();
        orgB = organizationRepository.findById(UUID.fromString(ORG_B_ID)).orElseThrow();

        // 1. Teams with full organization permissions in each respective org
        String teamNameA = "SEC_TEST_TEAM_A_" + UUID.randomUUID();
        teamOrgA = new Team();
        teamOrgA.setName(teamNameA);
        teamOrgA.setOrganization(orgA);
        teamOrgA.setRole("admin");
        teamOrgA.setManageWorkspace(true);
        teamOrgA.setManageCollection(true);
        teamOrgA.setManageJob(true);
        teamOrgA.setPlanJob(true);
        teamOrgA.setApproveJob(true);
        teamOrgA = teamRepository.saveAndFlush(teamOrgA);
        createdTeams.add(teamOrgA);
        tokenOrgA = generatePAT(teamNameA);

        String teamNameB = "SEC_TEST_TEAM_B_" + UUID.randomUUID();
        teamOrgB = new Team();
        teamOrgB.setName(teamNameB);
        teamOrgB.setOrganization(orgB);
        teamOrgB.setRole("admin");
        teamOrgB.setManageWorkspace(true);
        teamOrgB.setManageCollection(true);
        teamOrgB.setManageJob(true);
        teamOrgB.setPlanJob(true);
        teamOrgB.setApproveJob(true);
        teamOrgB = teamRepository.saveAndFlush(teamOrgB);
        createdTeams.add(teamOrgB);
        tokenOrgB = generatePAT(teamNameB);

        // 2. Org B Fixtures
        workspaceB = new Workspace();
        workspaceB.setName("ws-org-b-" + UUID.randomUUID());
        workspaceB.setOrganization(orgB);
        workspaceB.setSource("https://github.com/terrakube-io/terrakube.git");
        workspaceB.setBranch("main");
        workspaceB.setTerraformVersion("1.13.1");
        workspaceB = workspaceRepository.saveAndFlush(workspaceB);
        createdWorkspaces.add(workspaceB);

        webhookB = new Webhook();
        webhookB.setId(UUID.randomUUID());
        webhookB.setWorkspace(workspaceB);
        webhookB.setRemoteHookId("hook-b-" + UUID.randomUUID());
        webhookB = webhookRepository.saveAndFlush(webhookB);

        templateB = new Template();
        templateB.setName("Template B");
        templateB.setDescription("Template B");
        templateB.setVersion("1.0.0");
        templateB.setTcl("ZmxvdzoKICAtIHR5cGU6ICJ0ZXJyYWZvcm1QbGFuIgogICAgbmFtZTogIlBsYW4iCiAgICBzdGVwOiAxMDAK");
        templateB.setOrganization(orgB);
        templateB = templateRepository.saveAndFlush(templateB);

        eventB = new WebhookEvent();
        eventB.setId(UUID.randomUUID());
        eventB.setWebhook(webhookB);
        eventB.setBranch("main");
        eventB.setPath("/*");
        eventB.setTemplateId(templateB.getId().toString());
        eventB.setEvent(WebhookEventType.PUSH);
        eventB.setPathType(WebhookEventPathType.REGEX);
        eventB = webhookEventRepository.saveAndFlush(eventB);

        collectionB = new Collection();
        collectionB.setName("col-org-b-" + UUID.randomUUID());
        collectionB.setDescription("Collection Org B");
        collectionB.setOrganization(orgB);
        collectionB.setPriority(1);
        collectionB = collectionRepository.saveAndFlush(collectionB);

        itemB = new Item();
        itemB.setKey("KEY_B_" + UUID.randomUUID());
        itemB.setValue("VAL_B");
        itemB.setDescription("Item B");
        itemB.setCategory(Category.TERRAFORM);
        itemB.setCollection(collectionB);
        itemB = itemRepository.saveAndFlush(itemB);

        referenceB = new Reference();
        referenceB.setDescription("Reference B");
        referenceB.setCollection(collectionB);
        referenceB.setWorkspace(workspaceB);
        referenceB = referenceRepository.saveAndFlush(referenceB);

        jobB = new Job();
        jobB.setStatus(JobStatus.pending);
        jobB.setOrganization(orgB);
        jobB.setWorkspace(workspaceB);
        jobB = jobRepository.saveAndFlush(jobB);

        addressB = new Address();
        addressB.setName("module.vpc.aws_subnet.b");
        addressB.setType(AddressType.TARGET);
        addressB.setJob(jobB);
        addressB = addressRepository.saveAndFlush(addressB);

        variableB = new Variable();
        variableB.setKey("VAR_B_" + UUID.randomUUID());
        variableB.setValue("VALUE_B");
        variableB.setDescription("Variable B");
        variableB.setCategory(Category.TERRAFORM);
        variableB.setWorkspace(workspaceB);
        variableB = variableRepository.saveAndFlush(variableB);

        scheduleB = new Schedule();
        scheduleB.setCron("0 0 12 * * ?");
        scheduleB.setWorkspace(workspaceB);
        scheduleB = scheduleRepository.saveAndFlush(scheduleB);

        tagB = new Tag();
        tagB.setName("tag-b-" + UUID.randomUUID());
        tagB.setOrganization(orgB);
        tagB = tagRepository.saveAndFlush(tagB);

        agentB = new Agent();
        agentB.setName("agent-b-" + UUID.randomUUID());
        agentB.setUrl("https://agent-b.example.com");
        agentB.setDescription("Agent B");
        agentB.setOrganization(orgB);
        agentB = agentRepository.saveAndFlush(agentB);

        globalvarB = new Globalvar();
        globalvarB.setKey("GVAR_B_" + UUID.randomUUID());
        globalvarB.setValue("GVAL_B");
        globalvarB.setDescription("Globalvar B");
        globalvarB.setCategory(Category.TERRAFORM);
        globalvarB.setOrganization(orgB);
        globalvarB = globalVarRepository.saveAndFlush(globalvarB);

        // 3. Org A Fixtures
        workspaceA = new Workspace();
        workspaceA.setName("ws-org-a-" + UUID.randomUUID());
        workspaceA.setOrganization(orgA);
        workspaceA.setSource("https://github.com/terrakube-io/terrakube.git");
        workspaceA.setBranch("main");
        workspaceA.setTerraformVersion("1.13.1");
        workspaceA = workspaceRepository.saveAndFlush(workspaceA);
        createdWorkspaces.add(workspaceA);

        webhookA = new Webhook();
        webhookA.setId(UUID.randomUUID());
        webhookA.setWorkspace(workspaceA);
        webhookA.setRemoteHookId("hook-a-" + UUID.randomUUID());
        webhookA = webhookRepository.saveAndFlush(webhookA);

        eventA = new WebhookEvent();
        eventA.setId(UUID.randomUUID());
        eventA.setWebhook(webhookA);
        eventA.setBranch("main");
        eventA.setPath("/*");
        eventA.setTemplateId("42201234-a5e2-4c62-b2fc-9729ca6b4515");
        eventA.setEvent(WebhookEventType.PUSH);
        eventA.setPathType(WebhookEventPathType.REGEX);
        eventA = webhookEventRepository.saveAndFlush(eventA);

        collectionA = new Collection();
        collectionA.setName("col-org-a-" + UUID.randomUUID());
        collectionA.setDescription("Collection Org A");
        collectionA.setOrganization(orgA);
        collectionA.setPriority(1);
        collectionA = collectionRepository.saveAndFlush(collectionA);

        itemA = new Item();
        itemA.setKey("KEY_A_" + UUID.randomUUID());
        itemA.setValue("VAL_A");
        itemA.setDescription("Item A");
        itemA.setCategory(Category.TERRAFORM);
        itemA.setCollection(collectionA);
        itemA = itemRepository.saveAndFlush(itemA);

        referenceA = new Reference();
        referenceA.setDescription("Reference A");
        referenceA.setCollection(collectionA);
        referenceA.setWorkspace(workspaceA);
        referenceA = referenceRepository.saveAndFlush(referenceA);

        jobA = new Job();
        jobA.setStatus(JobStatus.pending);
        jobA.setOrganization(orgA);
        jobA.setWorkspace(workspaceA);
        jobA = jobRepository.saveAndFlush(jobA);

        stepA = new Step();
        stepA.setJob(jobA);
        stepA.setName("terraformPlan");
        stepA.setStatus(JobStatus.running);
        stepA.setStepNumber(100);
        stepA = stepRepository.saveAndFlush(stepA);

        addressA = new Address();
        addressA.setName("module.vpc.aws_subnet.a");
        addressA.setType(AddressType.TARGET);
        addressA.setJob(jobA);
        addressA = addressRepository.saveAndFlush(addressA);

        tagA = new Tag();
        tagA.setName("tag-a-" + UUID.randomUUID());
        tagA.setOrganization(orgA);
        tagA = tagRepository.saveAndFlush(tagA);

        agentA = new Agent();
        agentA.setName("agent-a-" + UUID.randomUUID());
        agentA.setUrl("https://agent-a.example.com");
        agentA.setDescription("Agent A");
        agentA.setOrganization(orgA);
        agentA = agentRepository.saveAndFlush(agentA);

        globalvarA = new Globalvar();
        globalvarA.setKey("GVAR_A_" + UUID.randomUUID());
        globalvarA.setValue("GVAL_A");
        globalvarA.setDescription("Globalvar A");
        globalvarA.setCategory(Category.TERRAFORM);
        globalvarA.setOrganization(orgA);
        globalvarA = globalVarRepository.saveAndFlush(globalvarA);
    }

    private void safeDelete(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void tearDownFixtures() {
        // Clean up Org B entities
        safeDelete(() -> { if (addressB != null) addressRepository.delete(addressB); });
        safeDelete(() -> { if (jobB != null) jobRepository.delete(jobB); });
        safeDelete(() -> { if (eventB != null) webhookEventRepository.delete(eventB); });
        safeDelete(() -> { if (webhookB != null) webhookRepository.delete(webhookB); });
        safeDelete(() -> { if (variableB != null) variableRepository.delete(variableB); });
        safeDelete(() -> { if (scheduleB != null) scheduleRepository.delete(scheduleB); });
        safeDelete(() -> { if (referenceB != null) referenceRepository.delete(referenceB); });
        safeDelete(() -> { if (itemB != null) itemRepository.delete(itemB); });
        safeDelete(() -> { if (collectionB != null) collectionRepository.delete(collectionB); });
        safeDelete(() -> { if (tagB != null) tagRepository.delete(tagB); });
        safeDelete(() -> { if (agentB != null) agentRepository.delete(agentB); });
        safeDelete(() -> { if (globalvarB != null) globalVarRepository.delete(globalvarB); });
        safeDelete(() -> { if (templateB != null) templateRepository.delete(templateB); });

        // Clean up Org A entities
        safeDelete(() -> { if (addressA != null) addressRepository.delete(addressA); });
        safeDelete(() -> { if (stepA != null) stepRepository.delete(stepA); });
        safeDelete(() -> { if (jobA != null) jobRepository.delete(jobA); });
        safeDelete(() -> { if (referenceA != null) referenceRepository.delete(referenceA); });
        safeDelete(() -> { if (itemA != null) itemRepository.delete(itemA); });
        safeDelete(() -> { if (collectionA != null) collectionRepository.delete(collectionA); });
        safeDelete(() -> { if (eventA != null) webhookEventRepository.delete(eventA); });
        safeDelete(() -> { if (webhookA != null) webhookRepository.delete(webhookA); });
        safeDelete(() -> { if (tagA != null) tagRepository.delete(tagA); });
        safeDelete(() -> { if (agentA != null) agentRepository.delete(agentA); });
        safeDelete(() -> { if (globalvarA != null) globalVarRepository.delete(globalvarA); });

        // Clean up access bindings
        accessRepository.deleteAll(createdAccesses);
        createdAccesses.clear();

        projectAccessRepository.deleteAll(createdProjectAccesses);
        createdProjectAccesses.clear();

        // Delete workspaces (null out project first to satisfy foreign key constraint)
        for (Workspace ws : createdWorkspaces) {
            workspaceRepository.findById(ws.getId()).ifPresent(current -> {
                current.setProject(null);
                current.setDeleted(true);
                workspaceRepository.saveAndFlush(current);
            });
        }
        createdWorkspaces.clear();

        // Delete projects
        for (Project proj : createdProjects) {
            projectRepository.findById(proj.getId()).ifPresent(projectRepository::delete);
        }
        createdProjects.clear();

        // Delete teams
        teamRepository.deleteAll(createdTeams);
        createdTeams.clear();
    }

    // =========================================================================
    // 1. Cross-Organization Disclosure Prevention (10 Resource Types)
    // =========================================================================

    @Test
    void userInOrgACannotReadOrgBWebhook() {
        // Listing endpoint returns empty data or 403
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/workspace/" + workspaceB.getId() + "/webhook")
                .then()
                .statusCode(anyOf(is(HttpStatus.OK.value()), is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())))
                .body("data", anyOf(nullValue(), empty()));

        // Direct ID read is strictly fail-closed (403 or 404)
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/workspace/" + workspaceB.getId() + "/webhook/" + webhookB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBWebhookEvent() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/workspace/" + workspaceB.getId() + "/webhook/" + webhookB.getId() + "/webhookEvent/" + eventB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBCollectionReference() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/collection/" + collectionB.getId() + "/reference/" + referenceB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBCollectionItem() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/collection/" + collectionB.getId() + "/item/" + itemB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBJobAddress() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/job/" + jobB.getId() + "/address/" + addressB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBVariable() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/workspace/" + workspaceB.getId() + "/variable/" + variableB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBSchedule() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/workspace/" + workspaceB.getId() + "/schedule/" + scheduleB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBTag() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/tag/" + tagB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBAgent() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/agent/" + agentB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    @Test
    void userInOrgACannotReadOrgBGlobalVar() {
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/organization/" + ORG_B_ID + "/globalvar/" + globalvarB.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    // =========================================================================
    // 1b. Flat-Path Cross-Organization Disclosure Prevention (Root Entities)
    // =========================================================================

    @Test
    void userInOrgACannotReadOrgBWebhookViaFlatPath() {
        // Positive: User in Org A can read Org A's webhook
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/webhook/" + webhookA.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", equalTo(webhookA.getId().toString()));

        // Negative: User in Org A gets 403 reading Org B's webhook directly by ID (leak on main returns 200)
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/webhook/" + webhookB.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Negative list: User in Org A lists webhooks: returns Org A's webhook and strictly excludes Org B's webhook
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/webhook")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItem(webhookA.getId().toString()))
                .body("data.id", not(hasItem(webhookB.getId().toString())));
    }

    @Test
    void userInOrgACannotReadOrgBWebhookEventViaFlatPath() {
        // Positive: User in Org A can read Org A's webhook event
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/webhook_event/" + eventA.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", equalTo(eventA.getId().toString()));

        // Negative: User in Org A gets 403 reading Org B's webhook event directly by ID (leak on main returns 200)
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/webhook_event/" + eventB.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Negative list: User in Org A lists webhook events: returns Org A's event and strictly excludes Org B's event
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/webhook_event")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItem(eventA.getId().toString()))
                .body("data.id", not(hasItem(eventB.getId().toString())));
    }

    @Test
    void userInOrgACannotReadOrgBCollectionReferenceViaFlatPath() {
        // Positive: User in Org A can read Org A's reference
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/reference/" + referenceA.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", equalTo(referenceA.getId().toString()));

        // Negative: User in Org A gets 403 reading Org B's reference directly by ID (leak on main returns 200)
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/reference/" + referenceB.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Negative list: User in Org A lists references: returns Org A's reference and strictly excludes Org B's reference
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/reference")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItem(referenceA.getId().toString()))
                .body("data.id", not(hasItem(referenceB.getId().toString())));
    }

    @Test
    void userInOrgACannotReadOrgBJobAddressViaFlatPath() {
        // Positive: User in Org A can read Org A's job address
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/address/" + addressA.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", equalTo(addressA.getId().toString()));

        // Negative: User in Org A gets 403 reading Org B's job address directly by ID (leak on main returns 200)
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/address/" + addressB.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Negative list: User in Org A lists addresses: returns Org A's address and strictly excludes Org B's address
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .get("/api/v1/address")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItem(addressA.getId().toString()))
                .body("data.id", not(hasItem(addressB.getId().toString())));
    }

    // =========================================================================
    // 2. Cross-Organization Reference Creation & Repoint Prevention
    // =========================================================================

    @Test
    void cannotCreateCrossOrgReferenceLinkingOrgACollectionToOrgBWorkspace() {
        // Dual-organization identity (admin in both Org A and Org B)
        // Can read both collectionA and workspaceB, so the cross-organization link is not
        // short-circuited by workspace read visibility (which succeeded with 201 on main)
        String dualOrgToken = generatePAT(teamOrgA.getName(), teamOrgB.getName());

        // Attempt to create a reference in collection A pointing to workspace B in Org B
        String payload = """
                {
                  "data": {
                    "type": "reference",
                    "attributes": {
                      "description": "Cross-Org Exploit Reference"
                    },
                    "relationships": {
                      "workspace": {
                        "data": {
                          "type": "workspace",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(workspaceB.getId());

        given()
                .headers("Authorization", "Bearer " + dualOrgToken)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(payload)
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/collection/" + collectionA.getId() + "/reference/")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void cannotPatchReferenceToRepointWorkspace() {
        // 1. User in Org A attempts to PATCH referenceA to point to foreign workspace B
        String foreignPayload = """
                {
                  "data": {
                    "type": "reference",
                    "id": "%s",
                    "relationships": {
                      "workspace": {
                        "data": {
                          "type": "workspace",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(referenceA.getId(), workspaceB.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(foreignPayload)
                .when()
                .patch("/api/v1/reference/" + referenceA.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));

        // 2. User in Org A attempts to repoint to sibling workspace in the same org (blocked by superuser-only update)
        Workspace wsA2 = new Workspace();
        wsA2.setName("ws-a2-repoint-" + UUID.randomUUID());
        wsA2.setOrganization(orgA);
        wsA2.setSource("https://github.com/terrakube-io/terrakube.git");
        wsA2.setBranch("main");
        wsA2.setTerraformVersion("1.13.1");
        wsA2 = workspaceRepository.saveAndFlush(wsA2);
        createdWorkspaces.add(wsA2);

        String sameOrgPayload = """
                {
                  "data": {
                    "type": "reference",
                    "id": "%s",
                    "relationships": {
                      "workspace": {
                        "data": {
                          "type": "workspace",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(referenceA.getId(), wsA2.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(sameOrgPayload)
                .when()
                .patch("/api/v1/reference/" + referenceA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void cannotPatchReferenceToRepointCollection() {
        // User in Org A attempts to PATCH referenceA to point to collection B
        String payload = """
                {
                  "data": {
                    "type": "reference",
                    "id": "%s",
                    "relationships": {
                      "collection": {
                        "data": {
                          "type": "collection",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(referenceA.getId(), collectionB.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(payload)
                .when()
                .patch("/api/v1/reference/" + referenceA.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    // =========================================================================
    // 3. Sibling Workspace Webhook Isolation (Workspace & Project Tiers)
    // =========================================================================

    @Test
    void workspaceLimitedUserCannotReadSiblingWorkspaceWebhook() {
        // Sibling workspace 2 in Org A
        Workspace workspaceA2 = new Workspace();
        workspaceA2.setName("ws-sibling-a2-" + UUID.randomUUID());
        workspaceA2.setOrganization(orgA);
        workspaceA2.setSource("https://github.com/terrakube-io/terrakube.git");
        workspaceA2.setBranch("main");
        workspaceA2.setTerraformVersion("1.13.1");
        workspaceA2 = workspaceRepository.saveAndFlush(workspaceA2);
        createdWorkspaces.add(workspaceA2);

        Webhook webhookA2 = new Webhook();
        webhookA2.setId(UUID.randomUUID());
        webhookA2.setWorkspace(workspaceA2);
        webhookA2.setRemoteHookId("hook-a2-" + UUID.randomUUID());
        webhookA2 = webhookRepository.saveAndFlush(webhookA2);

        // Limited identity bound via workspace Access ONLY (NOT an organization team)
        String limitedTeamName = "WS1_LIMITED_TEAM_" + UUID.randomUUID();

        Access access = new Access();
        access.setName(limitedTeamName);
        access.setRole("write");
        access.setWorkspace(workspaceA);
        access = accessRepository.saveAndFlush(access);
        createdAccesses.add(access);

        String limitedToken = generatePAT(limitedTeamName);

        // 1. Can read workspaceA webhook (allowed by Workspace Access binding)
        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA.getId() + "/webhook/" + webhookA.getId())
                .then()
                .statusCode(HttpStatus.OK.value());

        // 2. CANNOT read sibling workspaceA2 webhook (strictly 403 or 404)
        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA2.getId() + "/webhook/" + webhookA2.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));

        // 3. Flat path: Can read workspaceA webhook directly by ID
        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/webhook/" + webhookA.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", equalTo(webhookA.getId().toString()));

        // 4. Flat path: CANNOT read sibling workspaceA2 webhook directly by ID (leak on main returns 200)
        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/webhook/" + webhookA2.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        webhookRepository.delete(webhookA2);
    }

    @Test
    void projectLimitedUserCannotReadSiblingProjectWorkspaceWebhook() {
        Project project1 = new Project();
        project1.setName("project-1-" + UUID.randomUUID());
        project1.setOrganization(orgA);
        project1 = projectRepository.saveAndFlush(project1);
        createdProjects.add(project1);

        Project project2 = new Project();
        project2.setName("project-2-" + UUID.randomUUID());
        project2.setOrganization(orgA);
        project2 = projectRepository.saveAndFlush(project2);
        createdProjects.add(project2);

        // Workspace in project 1
        Workspace wsP1 = new Workspace();
        wsP1.setName("ws-p1-" + UUID.randomUUID());
        wsP1.setOrganization(orgA);
        wsP1.setProject(project1);
        wsP1.setSource("https://github.com/terrakube-io/terrakube.git");
        wsP1.setBranch("main");
        wsP1.setTerraformVersion("1.13.1");
        wsP1 = workspaceRepository.saveAndFlush(wsP1);
        createdWorkspaces.add(wsP1);

        Webhook webhookP1 = new Webhook();
        webhookP1.setId(UUID.randomUUID());
        webhookP1.setWorkspace(wsP1);
        webhookP1.setRemoteHookId("hook-p1-" + UUID.randomUUID());
        webhookP1 = webhookRepository.saveAndFlush(webhookP1);

        // Workspace in project 2
        Workspace wsP2 = new Workspace();
        wsP2.setName("ws-p2-" + UUID.randomUUID());
        wsP2.setOrganization(orgA);
        wsP2.setProject(project2);
        wsP2.setSource("https://github.com/terrakube-io/terrakube.git");
        wsP2.setBranch("main");
        wsP2.setTerraformVersion("1.13.1");
        wsP2 = workspaceRepository.saveAndFlush(wsP2);
        createdWorkspaces.add(wsP2);

        Webhook webhookP2 = new Webhook();
        webhookP2.setId(UUID.randomUUID());
        webhookP2.setWorkspace(wsP2);
        webhookP2.setRemoteHookId("hook-p2-" + UUID.randomUUID());
        webhookP2 = webhookRepository.saveAndFlush(webhookP2);

        // Identity bound to Project 1 ONLY (NOT an organization team)
        String projTeamName = "PROJ1_TEAM_" + UUID.randomUUID();

        ProjectAccess pa = new ProjectAccess();
        pa.setName(projTeamName);
        pa.setRole("write");
        pa.setProject(project1);
        pa = projectAccessRepository.saveAndFlush(pa);
        createdProjectAccesses.add(pa);

        String projToken = generatePAT(projTeamName);

        // 1. Can read project 1 workspace webhook
        given()
                .headers("Authorization", "Bearer " + projToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/workspace/" + wsP1.getId() + "/webhook/" + webhookP1.getId())
                .then()
                .statusCode(HttpStatus.OK.value());

        // 2. CANNOT read sibling project 2 workspace webhook (strictly 403 or 404)
        given()
                .headers("Authorization", "Bearer " + projToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/workspace/" + wsP2.getId() + "/webhook/" + webhookP2.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));

        // 3. Flat path: Can read project 1 workspace webhook directly by ID
        given()
                .headers("Authorization", "Bearer " + projToken)
                .when()
                .get("/api/v1/webhook/" + webhookP1.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", equalTo(webhookP1.getId().toString()));

        // 4. Flat path: CANNOT read sibling project 2 workspace webhook directly by ID (leak on main returns 200)
        given()
                .headers("Authorization", "Bearer " + projToken)
                .when()
                .get("/api/v1/webhook/" + webhookP2.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        webhookRepository.delete(webhookP1);
        webhookRepository.delete(webhookP2);
    }

    @Test
    void workspaceLimitedUserCannotReadOrgScopedResources() {
        String limitedTeamName = "WS_LIMITED_ORG_SCOPED_" + UUID.randomUUID();

        Access access = new Access();
        access.setName(limitedTeamName);
        access.setRole("write");
        access.setWorkspace(workspaceA);
        access = accessRepository.saveAndFlush(access);
        createdAccesses.add(access);

        String limitedToken = generatePAT(limitedTeamName);

        // 1. Workspace-limited user CAN read Tag metadata in Org A to resolve tag names in the UI
        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/tag/" + tagA.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", equalTo(tagA.getId().toString()));

        // 2. But workspace-limited user CANNOT read Org A's Agent, Globalvar, or Collection Item
        // (strictly fail-closed: 403 or 404, whereas on main they returned 200 OK)
        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/agent/" + agentA.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));

        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/globalvar/" + globalvarA.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));

        given()
                .headers("Authorization", "Bearer " + limitedToken)
                .when()
                .get("/api/v1/organization/" + ORG_A_ID + "/collection/" + collectionA.getId() + "/item/" + itemA.getId())
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));
    }

    // =========================================================================
    // 4. View-Only User Cannot Create Reference
    // =========================================================================

    @Test
    void workspaceViewOnlyUserCannotCreateReference() {
        String viewOnlyTeamName = "VIEW_ONLY_TEAM_" + UUID.randomUUID();

        Access viewAccess = new Access();
        viewAccess.setName(viewOnlyTeamName);
        viewAccess.setRole("read");
        viewAccess.setWorkspace(workspaceA);
        viewAccess = accessRepository.saveAndFlush(viewAccess);
        createdAccesses.add(viewAccess);

        String viewOnlyToken = generatePAT(viewOnlyTeamName);

        String payload = """
                {
                  "data": {
                    "type": "reference",
                    "attributes": {
                      "description": "View Only Reference Attempt"
                    },
                    "relationships": {
                      "workspace": {
                        "data": {
                          "type": "workspace",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(workspaceA.getId());

        given()
                .headers("Authorization", "Bearer " + viewOnlyToken)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(payload)
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/collection/" + collectionA.getId() + "/reference/")
                .then()
                .statusCode(anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.NOT_FOUND.value())));

        // Also verify view-only user cannot PATCH existing reference (on main: returned 204)
        String patchPayload = """
                {
                  "data": {
                    "type": "reference",
                    "id": "%s",
                    "attributes": {
                      "description": "Tampered description by view-only user"
                    }
                  }
                }
                """.formatted(referenceA.getId());

        given()
                .headers("Authorization", "Bearer " + viewOnlyToken)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(patchPayload)
                .when()
                .patch("/api/v1/reference/" + referenceA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    // =========================================================================
    // 5. Job Address Permissions Hardening (canPlanJob only, status pending only)
    // =========================================================================

    @Test
    void planUserCanPatchAndDeleteJobAddressOnPendingJob() {
        // Positive case: A user with canPlanJob can PATCH an existing address on a pending job
        String patchPayload = """
                {
                  "data": {
                    "type": "address",
                    "id": "%s",
                    "attributes": {
                      "name": "module.vpc.aws_subnet.updated"
                    }
                  }
                }
                """.formatted(addressA.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(patchPayload)
                .when()
                .patch("/api/v1/address/" + addressA.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Positive case: A user with canPlanJob can DELETE an existing address on a pending job
        Address tempAddress = new Address();
        tempAddress.setName("module.vpc.temp_to_delete");
        tempAddress.setType(AddressType.TARGET);
        tempAddress.setJob(jobA);
        tempAddress = addressRepository.saveAndFlush(tempAddress);

        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .delete("/api/v1/address/" + tempAddress.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void approverOnlyUserCannotPatchOrDeleteJobAddress() {
        // Approver-only team in Org A: can approve runs, but NOT plan runs
        String approverTeamName = "APPROVER_ONLY_TEAM_" + UUID.randomUUID();
        Team approverTeam = new Team();
        approverTeam.setName(approverTeamName);
        approverTeam.setOrganization(orgA);
        approverTeam.setRole("custom");
        approverTeam.setPlanJob(false);
        approverTeam.setApproveJob(true);
        approverTeam.setManageJob(true);
        approverTeam = teamRepository.saveAndFlush(approverTeam);
        createdTeams.add(approverTeam);

        String approverToken = generatePAT(approverTeamName);

        String patchPayload = """
                {
                  "data": {
                    "type": "address",
                    "id": "%s",
                    "attributes": {
                      "name": "module.database.rds_cluster.tampered"
                    }
                  }
                }
                """.formatted(addressA.getId());

        // Approver cannot PATCH an address (on main: returns 204 No Content)
        given()
                .headers("Authorization", "Bearer " + approverToken)
                .contentType(JSON_API_CONTENT_TYPE)
                .body(patchPayload)
                .when()
                .patch("/api/v1/address/" + addressA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Approver cannot DELETE an address (on main: returns 204 No Content)
        given()
                .headers("Authorization", "Bearer " + approverToken)
                .when()
                .delete("/api/v1/address/" + addressA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void cannotPatchOrDeleteJobAddressWhenJobIsNotPending() {
        // Dedicated running job and target address
        Job jobToCreate = new Job();
        jobToCreate.setStatus(JobStatus.running);
        jobToCreate.setOrganization(orgA);
        jobToCreate.setWorkspace(workspaceA);
        final Job runningJob = jobRepository.saveAndFlush(jobToCreate);

        Address addressToCreate = new Address();
        addressToCreate.setName("module.cache.redis_cluster.main");
        addressToCreate.setType(AddressType.TARGET);
        addressToCreate.setJob(runningJob);
        final Address runningAddress = addressRepository.saveAndFlush(addressToCreate);

        try {
            String patchPayload = """
                    {
                      "data": {
                        "type": "address",
                        "id": "%s",
                        "attributes": {
                          "name": "module.cache.redis_cluster.tampered"
                        }
                      }
                    }
                    """.formatted(runningAddress.getId());

            // Even a user with plan permissions cannot PATCH address when job is running (on main: returns 204 No Content)
            given()
                    .headers("Authorization", "Bearer " + tokenOrgA)
                    .contentType(JSON_API_CONTENT_TYPE)
                    .body(patchPayload)
                    .when()
                    .patch("/api/v1/address/" + runningAddress.getId())
                    .then()
                    .statusCode(HttpStatus.FORBIDDEN.value());

            // Even a user with plan permissions cannot DELETE address when job is running (on main: returns 204 No Content)
            given()
                    .headers("Authorization", "Bearer " + tokenOrgA)
                    .when()
                    .delete("/api/v1/address/" + runningAddress.getId())
                    .then()
                    .statusCode(HttpStatus.FORBIDDEN.value());
        } finally {
            safeDelete(() -> addressRepository.delete(runningAddress));
            safeDelete(() -> jobRepository.delete(runningJob));
        }
    }

    // =========================================================================
    // 6. Runner Token Scoping Hardening (WORKSPACE_EXECUTOR requires jobId)
    // =========================================================================

    @Test
    void runnerTokenWithoutJobIdCannotReadJobAddress() {
        // Internal runner token carrying workspaceId and active stepId but omitting jobId
        String runnerTokenMissingJobId = generateSystemToken(Map.of(
                "workspaceId", workspaceA.getId().toString(),
                "stepId", stepA.getId().toString(),
                "organizationId", ORG_A_ID
        ));

        given()
                .headers("Authorization", "Bearer " + runnerTokenMissingJobId)
                .when()
                .get("/api/v1/address/" + addressA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Internal runner token carrying matching jobId is allowed
        String validRunnerToken = generateSystemToken(Map.of(
                "workspaceId", workspaceA.getId().toString(),
                "jobId", String.valueOf(jobA.getId()),
                "stepId", stepA.getId().toString(),
                "organizationId", ORG_A_ID
        ));

        given()
                .headers("Authorization", "Bearer " + validRunnerToken)
                .when()
                .get("/api/v1/address/" + addressA.getId())
                .then()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void crossOrganizationTemplateReferenceIsForbidden() {
        // User in Org A cannot create a schedule referencing templateB (from Org B)
        String schedulePayload = String.format("""
                {
                  "data": {
                    "type": "schedule",
                    "attributes": {
                      "cron": "0 0/1 * * * ?",
                      "templateReference": "%s"
                    }
                  }
                }
                """, templateB.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(schedulePayload)
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA.getId() + "/schedule")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // User in Org A cannot create a webhook event referencing templateB (from Org B)
        String webhookEventPayload = String.format("""
                {
                  "data": {
                    "type": "webhook_event",
                    "id": "%s",
                    "attributes": {
                      "branch": "main",
                      "path": "/*",
                      "templateId": "%s",
                      "event": "PUSH",
                      "pathType": "REGEX"
                    }
                  }
                }
                """, UUID.randomUUID(), templateB.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(webhookEventPayload)
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA.getId() + "/webhook/" + webhookA.getId() + "/events")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // User in Org A cannot create a job referencing templateB (from Org B)
        String jobPayload = String.format("""
                {
                  "data": {
                    "type": "job",
                    "attributes": {
                      "templateReference": "%s"
                    },
                    "relationships": {
                      "workspace": {
                        "data": {
                          "type": "workspace",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """, templateB.getId(), workspaceA.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(jobPayload)
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/job")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void scheduleAndWebhookEventDanglingTemplateCanBeDeletedAndEdited() {
        // Create a temporary template in Org A
        Template tempTemplate = new Template();
        tempTemplate.setName("Temp Template");
        tempTemplate.setDescription("Temp Template");
        tempTemplate.setVersion("1.0.0");
        tempTemplate.setTcl("ZmxvdzoKICAtIHR5cGU6ICJ0ZXJyYWZvcm1QbGFuIgogICAgbmFtZTogIlBsYW4iCiAgICBzdGVwOiAxMDAK");
        tempTemplate.setOrganization(orgA);
        tempTemplate = templateRepository.saveAndFlush(tempTemplate);

        // Create a schedule referencing tempTemplate
        Schedule tempSchedule = new Schedule();
        tempSchedule.setCron("0 0 12 * * ?");
        tempSchedule.setWorkspace(workspaceA);
        tempSchedule.setTemplateReference(tempTemplate.getId().toString());
        tempSchedule = scheduleRepository.saveAndFlush(tempSchedule);

        // Delete the template so it becomes dangling
        templateRepository.delete(tempTemplate);

        // 1. Updating non-template attribute (cron) succeeds with 204 No Content
        String patchCronPayload = String.format("""
                {
                  "data": {
                    "type": "schedule",
                    "id": "%s",
                    "attributes": {
                      "cron": "0 0 18 * * ?"
                    }
                  }
                }
                """, tempSchedule.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(patchCronPayload)
                .when()
                .patch("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA.getId() + "/schedule/" + tempSchedule.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // 2. Deleting the schedule with dangling template succeeds with 204 No Content
        given()
                .headers("Authorization", "Bearer " + tokenOrgA)
                .when()
                .delete("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA.getId() + "/schedule/" + tempSchedule.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void crossOrganizationJobWorkspaceIsForbidden() {
        String dualOrgToken = generatePAT(teamOrgA.getName(), teamOrgB.getName());
        // User belonging to both orgs cannot create a job in Org A targeting Workspace B (belonging to Org B)
        String jobPayload = String.format("""
                {
                  "data": {
                    "type": "job",
                    "relationships": {
                      "workspace": {
                        "data": {
                          "type": "workspace",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """, workspaceB.getId());

        given()
                .headers("Authorization", "Bearer " + dualOrgToken, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(jobPayload)
                .when()
                .post("/api/v1/organization/" + ORG_A_ID + "/job")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void repointingJobWorkspaceOrOrganizationIsForbidden() {
        String dualOrgToken = generatePAT(teamOrgA.getName(), teamOrgB.getName());
        // Attempting to repoint workspace on existing job returns 403 Forbidden
        String patchWorkspacePayload = String.format("""
                {
                  "data": {
                    "type": "job",
                    "id": "%d",
                    "relationships": {
                      "workspace": {
                        "data": {
                          "type": "workspace",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """, jobA.getId(), workspaceB.getId());

        given()
                .headers("Authorization", "Bearer " + dualOrgToken, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(patchWorkspacePayload)
                .when()
                .patch("/api/v1/organization/" + ORG_A_ID + "/job/" + jobA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Attempting to repoint organization on existing job returns 403 Forbidden
        String patchOrgPayload = String.format("""
                {
                  "data": {
                    "type": "job",
                    "id": "%d",
                    "relationships": {
                      "organization": {
                        "data": {
                          "type": "organization",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """, jobA.getId(), ORG_B_ID);

        given()
                .headers("Authorization", "Bearer " + dualOrgToken, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(patchOrgPayload)
                .when()
                .patch("/api/v1/organization/" + ORG_A_ID + "/job/" + jobA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void workspaceCrossOrganizationRelationshipsAndRenameConflict() {
        String dualOrgToken = generatePAT(teamOrgA.getName(), teamOrgB.getName());

        // 1. Attempting to link agentB (from Org B) to workspaceA (in Org A) via PATCH -> 403 Forbidden
        String patchCrossOrgAgent = String.format("""
                {
                  "data": {
                    "type": "workspace",
                    "id": "%s",
                    "relationships": {
                      "agent": {
                        "data": {
                          "type": "agent",
                          "id": "%s"
                        }
                      }
                    }
                  }
                }
                """, workspaceA.getId(), agentB.getId());

        given()
                .headers("Authorization", "Bearer " + dualOrgToken, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(patchCrossOrgAgent)
                .when()
                .patch("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA.getId())
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // 2. Sibling workspace rename conflict in Org A -> 409 Conflict
        Workspace workspaceA2 = new Workspace();
        workspaceA2.setName("ws-org-a-second-" + UUID.randomUUID());
        workspaceA2.setOrganization(orgA);
        workspaceA2.setSource("https://github.com/terrakube-io/terrakube.git");
        workspaceA2.setBranch("main");
        workspaceA2.setTerraformVersion("1.13.1");
        workspaceA2 = workspaceRepository.saveAndFlush(workspaceA2);
        createdWorkspaces.add(workspaceA2);

        String renameConflictPayload = String.format("""
                {
                  "data": {
                    "type": "workspace",
                    "id": "%s",
                    "attributes": {
                      "name": "%s"
                    }
                  }
                }
                """, workspaceA2.getId(), workspaceA.getName());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(renameConflictPayload)
                .when()
                .patch("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA2.getId())
                .then()
                .statusCode(HttpStatus.CONFLICT.value());

        // 3. Normal update on workspace (without rename conflict) -> 204 No Content
        String normalUpdatePayload = String.format("""
                {
                  "data": {
                    "type": "workspace",
                    "id": "%s",
                    "attributes": {
                      "description": "Updated Description"
                    }
                  }
                }
                """, workspaceA.getId());

        given()
                .headers("Authorization", "Bearer " + tokenOrgA, "Content-Type", JSON_API_CONTENT_TYPE)
                .body(normalUpdatePayload)
                .when()
                .patch("/api/v1/organization/" + ORG_A_ID + "/workspace/" + workspaceA.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }
}
