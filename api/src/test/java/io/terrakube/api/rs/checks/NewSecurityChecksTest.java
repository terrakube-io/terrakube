package io.terrakube.api.rs.checks;

import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.action.Action;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.checks.action.ReadAction;
import io.terrakube.api.rs.checks.agent.TeamViewAgent;
import io.terrakube.api.rs.checks.collection.TeamManageCollectionItem;
import io.terrakube.api.rs.checks.collection.TeamManageCollectionReference;
import io.terrakube.api.rs.checks.collection.TeamViewCollectionItem;
import io.terrakube.api.rs.checks.collection.TeamViewCollectionReference;
import io.terrakube.api.rs.checks.collection.UpdateCollectionSecret;
import io.terrakube.api.rs.checks.globalvar.TeamViewGlobalvar;
import io.terrakube.api.rs.checks.job.*;
import io.terrakube.api.rs.checks.job.address.TeamManageJobAddress;
import io.terrakube.api.rs.checks.job.address.TeamViewJobAddress;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.checks.module.TeamViewModuleVersion;
import io.terrakube.api.rs.checks.notification.NotificationCheckSupport;
import io.terrakube.api.rs.checks.notification.TeamManageNotificationTrigger;
import io.terrakube.api.rs.checks.notification.TeamReadNotificationTrigger;
import io.terrakube.api.rs.checks.provider.TeamManageProviderImplementation;
import io.terrakube.api.rs.checks.provider.TeamViewProviderImplementation;
import io.terrakube.api.rs.checks.provider.TeamViewProviderVersion;
import io.terrakube.api.rs.checks.tag.TeamManageTag;
import io.terrakube.api.rs.checks.tag.TeamViewTag;
import io.terrakube.api.rs.checks.team.TeamViewTeam;
import io.terrakube.api.rs.checks.variable.TeamManageVariable;
import io.terrakube.api.rs.checks.variable.TeamViewVariable;
import io.terrakube.api.rs.checks.variable.UpdateVariableSecret;
import io.terrakube.api.rs.checks.webhook.TeamDeleteWebhookEvent;
import io.terrakube.api.rs.checks.webhook.TeamManageWebhook;
import io.terrakube.api.rs.checks.webhook.TeamManageWebhookEvent;
import io.terrakube.api.rs.checks.webhook.TeamViewWebhook;
import io.terrakube.api.rs.checks.webhook.TeamViewWebhookEvent;
import io.terrakube.api.rs.checks.workspace.TeamDeleteSchedule;
import io.terrakube.api.rs.checks.workspace.TeamManageSchedule;
import io.terrakube.api.rs.checks.workspace.TeamManageWorkspaceTag;
import io.terrakube.api.rs.checks.workspace.TeamViewSchedule;
import io.terrakube.api.rs.checks.workspace.TeamViewWorkspaceTag;
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.hooks.workspace.WorkspaceManageHook;
import io.terrakube.api.rs.hooks.workspace.WorkspaceManagementException;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationTrigger;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Implementation;
import io.terrakube.api.rs.provider.implementation.Version;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NewSecurityChecksTest {

    @Mock
    private AuthenticatedUser authenticatedUser;

    @Mock
    private GroupService groupService;

    @Mock
    private RbacService rbacService;

    @Mock
    private MembershipService membershipService;

    @Mock
    private io.terrakube.api.repository.TemplateRepository templateRepository;

    @Mock
    private RequestScope requestScope;

    @Mock
    private User user;

    @InjectMocks
    private WorkspaceCheckSupport workspaceCheckSupport;

    @InjectMocks
    private NotificationCheckSupport notificationCheckSupport;

    @BeforeEach
    void setUp() {
        when(requestScope.getUser()).thenReturn(user);
    }

    @Test
    void testReadAction() {
        ReadAction check = new ReadAction();
        assertFalse(check.ok((User) null));

        when(user.getPrincipal()).thenReturn(null);
        assertFalse(check.ok(user));

        Principal principal = mock(Principal.class);
        when(user.getPrincipal()).thenReturn(principal);
        assertTrue(check.ok(user));
    }

    @Test
    void testTeamViewTeam() {
        TeamViewTeam check = new TeamViewTeam();
        ReflectionTestUtils.setField(check, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(check, "groupService", groupService);
        ReflectionTestUtils.setField(check, "membershipService", membershipService);

        Organization org = new Organization();
        Team team = new Team();
        team.setName("devs");
        team.setOrganization(org);
        org.setTeam(List.of(team));

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(true);

        assertTrue(check.ok(team, requestScope, Optional.empty()));

        when(membershipService.checkMembership(user, List.of(team))).thenReturn(false);
        assertFalse(check.ok(team, requestScope, Optional.empty()));
    }

    @Test
    void testTeamViewAgent() {
        TeamViewAgent check = new TeamViewAgent();
        ReflectionTestUtils.setField(check, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(check, "membershipService", membershipService);

        Organization org = new Organization();
        Team team = new Team();
        org.setTeam(List.of(team));

        Agent agent = new Agent();
        agent.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(true);

        assertTrue(check.ok(agent, requestScope, Optional.empty()));
    }

    @Test
    void testTeamTagChecks() {
        TeamViewTag viewCheck = new TeamViewTag();
        ReflectionTestUtils.setField(viewCheck, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewCheck, "membershipService", membershipService);
        ReflectionTestUtils.setField(viewCheck, "groupService", groupService);

        TeamManageTag manageCheck = new TeamManageTag();
        ReflectionTestUtils.setField(manageCheck, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(manageCheck, "membershipService", membershipService);
        ReflectionTestUtils.setField(manageCheck, "rbacService", rbacService);

        Organization org = new Organization();
        Team team = new Team();
        org.setTeam(List.of(team));

        Tag tag = new Tag();
        tag.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(true);
        when(membershipService.checkTeamPermission(eq(user), eq(List.of(team)), any())).thenReturn(true);

        assertTrue(viewCheck.ok(tag, requestScope, Optional.empty()));
        assertTrue(manageCheck.ok(tag, requestScope, Optional.empty()));

        // Workspace-limited user in the organization can view tag (for tag name resolution)
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(false);
        when(groupService.isMemberWithLimitedAccessV2(user, org)).thenReturn(true);
        assertTrue(viewCheck.ok(tag, requestScope, Optional.empty()));

        // Project-limited user in the organization can view tag
        when(groupService.isMemberWithLimitedAccessV2(user, org)).thenReturn(false);
        when(groupService.isMemberWithProjectAccess(user, org)).thenReturn(true);
        assertTrue(viewCheck.ok(tag, requestScope, Optional.empty()));

        // User with no access cannot view tag
        when(groupService.isMemberWithProjectAccess(user, org)).thenReturn(false);
        assertFalse(viewCheck.ok(tag, requestScope, Optional.empty()));
    }

    @Test
    void testWorkspaceChildrenChecks() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        Team team = new Team();
        org.setTeam(List.of(team));

        Workspace ws = new Workspace();
        ws.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        // Variable
        TeamViewVariable viewVar = new TeamViewVariable();
        ReflectionTestUtils.setField(viewVar, "workspaceCheckSupport", workspaceCheckSupport);
        TeamManageVariable manageVar = new TeamManageVariable();
        ReflectionTestUtils.setField(manageVar, "workspaceCheckSupport", workspaceCheckSupport);
        Variable var = new Variable();
        var.setWorkspace(ws);
        assertTrue(viewVar.ok(var, requestScope, Optional.empty()));
        assertTrue(manageVar.ok(var, requestScope, Optional.empty()));

        // Schedule
        TeamViewSchedule viewSched = new TeamViewSchedule();
        ReflectionTestUtils.setField(viewSched, "workspaceCheckSupport", workspaceCheckSupport);
        TeamManageSchedule manageSched = new TeamManageSchedule();
        ReflectionTestUtils.setField(manageSched, "workspaceCheckSupport", workspaceCheckSupport);
        Schedule sched = new Schedule();
        sched.setWorkspace(ws);
        assertTrue(viewSched.ok(sched, requestScope, Optional.empty()));
        assertTrue(manageSched.ok(sched, requestScope, Optional.empty()));

        // WorkspaceTag
        TeamViewWorkspaceTag viewWsTag = new TeamViewWorkspaceTag();
        ReflectionTestUtils.setField(viewWsTag, "workspaceCheckSupport", workspaceCheckSupport);
        TeamManageWorkspaceTag manageWsTag = new TeamManageWorkspaceTag();
        ReflectionTestUtils.setField(manageWsTag, "workspaceCheckSupport", workspaceCheckSupport);
        WorkspaceTag wsTag = new WorkspaceTag();
        wsTag.setWorkspace(ws);
        assertTrue(viewWsTag.ok(wsTag, requestScope, Optional.empty()));
        assertTrue(manageWsTag.ok(wsTag, requestScope, Optional.empty()));

        // Reference (same organization)
        Collection col = new Collection();
        col.setOrganization(org);

        TeamViewCollectionReference viewRef = new TeamViewCollectionReference();
        ReflectionTestUtils.setField(viewRef, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewRef, "membershipService", membershipService);
        ReflectionTestUtils.setField(viewRef, "workspaceCheckSupport", workspaceCheckSupport);
        TeamManageCollectionReference manageRef = new TeamManageCollectionReference();
        ReflectionTestUtils.setField(manageRef, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(manageRef, "membershipService", membershipService);
        ReflectionTestUtils.setField(manageRef, "rbacService", rbacService);
        Reference ref = new Reference();
        ref.setWorkspace(ws);
        ref.setCollection(col);
        assertTrue(viewRef.ok(ref, requestScope, Optional.empty()));
        assertTrue(manageRef.ok(ref, requestScope, Optional.empty()));

        // Webhook
        TeamViewWebhook viewWh = new TeamViewWebhook();
        ReflectionTestUtils.setField(viewWh, "workspaceCheckSupport", workspaceCheckSupport);
        TeamManageWebhook manageWh = new TeamManageWebhook();
        ReflectionTestUtils.setField(manageWh, "workspaceCheckSupport", workspaceCheckSupport);
        Webhook wh = new Webhook();
        wh.setWorkspace(ws);
        assertTrue(viewWh.ok(wh, requestScope, Optional.empty()));
        assertTrue(manageWh.ok(wh, requestScope, Optional.empty()));

        // WebhookEvent
        TeamViewWebhookEvent viewWhEvent = new TeamViewWebhookEvent();
        ReflectionTestUtils.setField(viewWhEvent, "workspaceCheckSupport", workspaceCheckSupport);
        TeamManageWebhookEvent manageWhEvent = new TeamManageWebhookEvent();
        ReflectionTestUtils.setField(manageWhEvent, "workspaceCheckSupport", workspaceCheckSupport);
        WebhookEvent whEvent = new WebhookEvent();
        whEvent.setWebhook(wh);
        assertTrue(viewWhEvent.ok(whEvent, requestScope, Optional.empty()));
        assertTrue(manageWhEvent.ok(whEvent, requestScope, Optional.empty()));
    }

    @Test
    void testCollectionReferenceCrossOrgAndPermissionChecks() {
        Organization org1 = new Organization();
        org1.setId(UUID.randomUUID());
        Organization org2 = new Organization();
        org2.setId(UUID.randomUUID());

        Collection colOrg1 = new Collection();
        colOrg1.setOrganization(org1);

        Workspace wsOrg2 = new Workspace();
        wsOrg2.setOrganization(org2);

        Reference crossOrgRef = new Reference();
        crossOrgRef.setCollection(colOrg1);
        crossOrgRef.setWorkspace(wsOrg2);

        TeamManageCollectionReference manageRef = new TeamManageCollectionReference();
        ReflectionTestUtils.setField(manageRef, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(manageRef, "membershipService", membershipService);
        ReflectionTestUtils.setField(manageRef, "rbacService", rbacService);

        TeamViewCollectionReference viewRef = new TeamViewCollectionReference();
        ReflectionTestUtils.setField(viewRef, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewRef, "membershipService", membershipService);
        ReflectionTestUtils.setField(viewRef, "workspaceCheckSupport", workspaceCheckSupport);

        // Superuser can view and manage any reference (including cleanup of legacy or broken rows)
        when(authenticatedUser.isSuperUser(user)).thenReturn(true);
        assertTrue(manageRef.ok(crossOrgRef, requestScope, Optional.empty()));
        assertTrue(viewRef.ok(crossOrgRef, requestScope, Optional.empty()));

        Reference nullColRef = new Reference();
        nullColRef.setWorkspace(wsOrg2);
        assertTrue(manageRef.ok(nullColRef, requestScope, Optional.empty()));
        assertTrue(viewRef.ok(nullColRef, requestScope, Optional.empty()));

        // Non-superuser must satisfy structural validity and permissions
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        // Cross-org reference MUST be rejected for non-superuser
        assertFalse(manageRef.ok(crossOrgRef, requestScope, Optional.empty()));
        assertFalse(viewRef.ok(crossOrgRef, requestScope, Optional.empty()));

        // Reference with null collection MUST be rejected for non-superuser
        assertFalse(manageRef.ok(nullColRef, requestScope, Optional.empty()));

        // Non-superuser without canManageCollection must be rejected
        Workspace wsOrg1 = new Workspace();
        wsOrg1.setOrganization(org1);
        Reference sameOrgRef = new Reference();
        sameOrgRef.setCollection(colOrg1);
        sameOrgRef.setWorkspace(wsOrg1);

        Team team = new Team();
        org1.setTeam(List.of(team));
        when(membershipService.checkTeamPermission(eq(user), eq(List.of(team)), any())).thenReturn(false);
        assertFalse(manageRef.ok(sameOrgRef, requestScope, Optional.empty()));

        // With canManageCollection, same-org reference is allowed
        when(membershipService.checkTeamPermission(eq(user), eq(List.of(team)), any())).thenReturn(true);
        assertTrue(manageRef.ok(sameOrgRef, requestScope, Optional.empty()));
    }

    @Test
    void testJobAddressChecks() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        Team team = new Team();
        org.setTeam(List.of(team));

        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setOrganization(org);

        Job job = new Job();
        job.setId(101);
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(JobStatus.pending);

        Address addr = new Address();
        addr.setJob(job);

        TeamViewJobAddress viewAddr = new TeamViewJobAddress();
        ReflectionTestUtils.setField(viewAddr, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewAddr, "membershipService", membershipService);

        TeamManageJobAddress manageAddr = new TeamManageJobAddress();
        ReflectionTestUtils.setField(manageAddr, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(manageAddr, "membershipService", membershipService);
        ReflectionTestUtils.setField(manageAddr, "rbacService", rbacService);

        // 1. Superuser
        when(authenticatedUser.isSuperUser(user)).thenReturn(true);
        assertTrue(viewAddr.ok(addr, requestScope, Optional.empty()));
        assertTrue(manageAddr.ok(addr, requestScope, Optional.empty()));

        // 2. Non-superuser org member
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(true);
        when(membershipService.checkTeamPermission(eq(user), eq(List.of(team)), any())).thenReturn(true);
        assertTrue(viewAddr.ok(addr, requestScope, Optional.empty()));
        assertTrue(manageAddr.ok(addr, requestScope, Optional.empty()));

        // 3. Address mutation on non-pending or null-status job is forbidden
        job.setStatus(JobStatus.running);
        assertFalse(manageAddr.ok(addr, requestScope, Optional.empty()));
        job.setStatus(null);
        assertFalse(manageAddr.ok(addr, requestScope, Optional.empty()));
        job.setStatus(JobStatus.pending);

        // 4. Scoped executor token for view
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(false);
        when(membershipService.checkLimitedMembership(any(), any())).thenReturn(false);
        assertFalse(viewAddr.ok(addr, requestScope, Optional.empty()));

        JwtAuthenticationToken jwt = mock(JwtAuthenticationToken.class);
        when(user.getPrincipal()).thenReturn(jwt);

        // Missing jobId claim in runner token must be rejected
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", ws.getId().toString()
        ));
        assertFalse(viewAddr.ok(addr, requestScope, Optional.empty()));

        // Mismatched jobId claim must be rejected
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", ws.getId().toString(),
                "jobId", "999"
        ));
        assertFalse(viewAddr.ok(addr, requestScope, Optional.empty()));

        // Valid matching claims allowed
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", ws.getId().toString(),
                "jobId", "101"
        ));
        assertTrue(viewAddr.ok(addr, requestScope, Optional.empty()));
    }

    @Test
    void testNotificationTriggerChecks() {
        Organization org = new Organization();
        Team team = new Team();
        team.setName("admin-team");
        org.setTeam(List.of(team));

        NotificationConfiguration config = new NotificationConfiguration();
        config.setOrganization(org);

        NotificationTrigger trigger = new NotificationTrigger();
        trigger.setConfiguration(config);

        TeamReadNotificationTrigger readCheck = new TeamReadNotificationTrigger();
        ReflectionTestUtils.setField(readCheck, "notificationCheckSupport", notificationCheckSupport);
        TeamManageNotificationTrigger manageCheck = new TeamManageNotificationTrigger();
        ReflectionTestUtils.setField(manageCheck, "notificationCheckSupport", notificationCheckSupport);

        // Superuser can access notification trigger
        when(authenticatedUser.isSuperUser(user)).thenReturn(true);
        assertTrue(readCheck.ok(trigger, requestScope, Optional.empty()));
        assertTrue(manageCheck.ok(trigger, requestScope, Optional.empty()));

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(authenticatedUser.isServiceAccount(user)).thenReturn(false);
        when(authenticatedUser.isFederatedAccount(user)).thenReturn(false);
        when(groupService.isMember(user, "admin-team")).thenReturn(true);
        when(rbacService.canManageWorkspace(team)).thenReturn(true);
        when(membershipService.checkTeamPermission(eq(user), eq(List.of(team)), any())).thenReturn(true);

        assertTrue(readCheck.ok(trigger, requestScope, Optional.empty()));
        assertTrue(manageCheck.ok(trigger, requestScope, Optional.empty()));
    }

    @Test
    void testModuleAndProviderChecks() {
        Organization org = new Organization();
        Team team = new Team();
        org.setTeam(List.of(team));

        Module module = new Module();
        module.setOrganization(org);
        ModuleVersion modVer = new ModuleVersion();
        modVer.setModule(module);

        TeamViewModuleVersion viewModVer = new TeamViewModuleVersion();
        ReflectionTestUtils.setField(viewModVer, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewModVer, "membershipService", membershipService);
        ReflectionTestUtils.setField(viewModVer, "groupService", groupService);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(true);

        assertTrue(viewModVer.ok(modVer, requestScope, Optional.empty()));

        // Fallback to limited / project access
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(false);
        when(groupService.isMemberWithLimitedAccessV2(user, org)).thenReturn(false);
        when(groupService.isMemberWithProjectAccess(user, org)).thenReturn(true);
        assertTrue(viewModVer.ok(modVer, requestScope, Optional.empty()));

        // Provider Version & Implementation
        Provider provider = new Provider();
        provider.setOrganization(org);
        Version provVer = new Version();
        provVer.setProvider(provider);
        Implementation impl = new Implementation();
        impl.setVersion(provVer);

        TeamViewProviderVersion viewProvVer = new TeamViewProviderVersion();
        ReflectionTestUtils.setField(viewProvVer, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewProvVer, "membershipService", membershipService);
        ReflectionTestUtils.setField(viewProvVer, "groupService", groupService);

        TeamViewProviderImplementation viewImpl = new TeamViewProviderImplementation();
        ReflectionTestUtils.setField(viewImpl, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewImpl, "membershipService", membershipService);
        ReflectionTestUtils.setField(viewImpl, "groupService", groupService);

        TeamManageProviderImplementation manageImpl = new TeamManageProviderImplementation();
        ReflectionTestUtils.setField(manageImpl, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(manageImpl, "membershipService", membershipService);
        ReflectionTestUtils.setField(manageImpl, "rbacService", rbacService);

        when(membershipService.checkTeamPermission(eq(user), eq(List.of(team)), any())).thenReturn(true);

        assertTrue(viewProvVer.ok(provVer, requestScope, Optional.empty()));
        assertTrue(viewImpl.ok(impl, requestScope, Optional.empty()));
        assertTrue(manageImpl.ok(impl, requestScope, Optional.empty()));
    }

    @Test
    void testCollectionItemChecks() {
        Organization org = new Organization();
        Team team = new Team();
        org.setTeam(List.of(team));

        Collection col = new Collection();
        col.setOrganization(org);
        Item item = new Item();
        item.setCollection(col);

        TeamViewCollectionItem viewItem = new TeamViewCollectionItem();
        ReflectionTestUtils.setField(viewItem, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewItem, "membershipService", membershipService);

        TeamManageCollectionItem manageItem = new TeamManageCollectionItem();
        ReflectionTestUtils.setField(manageItem, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(manageItem, "membershipService", membershipService);
        ReflectionTestUtils.setField(manageItem, "rbacService", rbacService);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(user, List.of(team))).thenReturn(true);
        when(membershipService.checkTeamPermission(eq(user), eq(List.of(team)), any())).thenReturn(true);

        assertTrue(viewItem.ok(item, requestScope, Optional.empty()));
        assertTrue(manageItem.ok(item, requestScope, Optional.empty()));
    }

    @Test
    void testMembershipServiceFederated() {
        MembershipService realMembershipService = new MembershipService();
        ReflectionTestUtils.setField(realMembershipService, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(realMembershipService, "groupService", groupService);

        Team team = new Team();
        team.setName("fed-team");

        when(authenticatedUser.isServiceAccount(user)).thenReturn(false);
        when(authenticatedUser.isFederatedAccount(user)).thenReturn(true);
        when(groupService.isFederatedMember(user, "fed-team")).thenReturn(true);

        assertTrue(realMembershipService.checkMembership(user, List.of(team)));
        assertTrue(realMembershipService.checkTeamPermission(user, List.of(team), t -> true));

        Access access = new Access();
        access.setName("fed-team");
        assertTrue(realMembershipService.checkLimitedMembership(user, List.of(access)));
        assertTrue(realMembershipService.checkAccessPermission(user, List.of(access), a -> true));
    }

    @Test
    void testRelationshipUpdatePermissions() throws NoSuchFieldException {
        com.yahoo.elide.annotation.UpdatePermission refColPerm =
                Reference.class.getDeclaredField("collection").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(refColPerm);
        org.junit.jupiter.api.Assertions.assertEquals("user is a superuser", refColPerm.expression());

        com.yahoo.elide.annotation.UpdatePermission refWsPerm =
                Reference.class.getDeclaredField("workspace").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(refWsPerm);
        org.junit.jupiter.api.Assertions.assertEquals("user is a superuser", refWsPerm.expression());

        com.yahoo.elide.annotation.UpdatePermission addrJobPerm =
                Address.class.getDeclaredField("job").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(addrJobPerm);
        org.junit.jupiter.api.Assertions.assertEquals("user is a superuser", addrJobPerm.expression());

        com.yahoo.elide.annotation.UpdatePermission wsRefPerm =
                Workspace.class.getDeclaredField("reference").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(wsRefPerm);
        org.junit.jupiter.api.Assertions.assertEquals(
                "team view workspace OR team project limited view workspace OR team limited view workspace",
                wsRefPerm.expression());

        com.yahoo.elide.annotation.UpdatePermission jobOrgPerm =
                Job.class.getDeclaredField("organization").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(jobOrgPerm);
        org.junit.jupiter.api.Assertions.assertEquals("user is a superuser", jobOrgPerm.expression());

        com.yahoo.elide.annotation.UpdatePermission jobWsPerm =
                Job.class.getDeclaredField("workspace").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(jobWsPerm);
        org.junit.jupiter.api.Assertions.assertEquals("user is a superuser", jobWsPerm.expression());

        com.yahoo.elide.annotation.UpdatePermission varSensPerm =
                Variable.class.getDeclaredField("sensitive").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(varSensPerm);
        org.junit.jupiter.api.Assertions.assertEquals("team manage variable AND user update secret", varSensPerm.expression());

        com.yahoo.elide.annotation.UpdatePermission itemSensPerm =
                Item.class.getDeclaredField("sensitive").getAnnotation(com.yahoo.elide.annotation.UpdatePermission.class);
        org.junit.jupiter.api.Assertions.assertNotNull(itemSensPerm);
        org.junit.jupiter.api.Assertions.assertEquals("team manage collection item AND user update collection secret", itemSensPerm.expression());
    }

    @Test
    void testTemplateOrganizationValidation() {
        UUID orgAId = UUID.randomUUID();
        UUID orgBId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        Organization orgA = new Organization();
        orgA.setId(orgAId);
        Organization orgB = new Organization();
        orgB.setId(orgBId);

        Workspace workspaceA = new Workspace();
        workspaceA.setId(UUID.randomUUID());
        workspaceA.setOrganization(orgA);

        io.terrakube.api.rs.template.Template templateA = new io.terrakube.api.rs.template.Template();
        templateA.setId(templateId);
        templateA.setOrganization(orgA);

        io.terrakube.api.rs.template.Template templateB = new io.terrakube.api.rs.template.Template();
        templateB.setId(templateId);
        templateB.setOrganization(orgB);

        // Null or blank template is allowed
        assertTrue(workspaceCheckSupport.isTemplateInWorkspaceOrganization(workspaceA, null));
        assertTrue(workspaceCheckSupport.isTemplateInWorkspaceOrganization(workspaceA, ""));

        // Non-existent template is rejected
        when(templateRepository.findById(templateId)).thenReturn(Optional.empty());
        assertFalse(workspaceCheckSupport.isTemplateInWorkspaceOrganization(workspaceA, templateId.toString()));

        // Mismatched organization template is rejected
        when(templateRepository.findById(templateId)).thenReturn(Optional.of(templateB));
        assertFalse(workspaceCheckSupport.isTemplateInWorkspaceOrganization(workspaceA, templateId.toString()));

        // Matching organization template is accepted
        when(templateRepository.findById(templateId)).thenReturn(Optional.of(templateA));
        assertTrue(workspaceCheckSupport.isTemplateInWorkspaceOrganization(workspaceA, templateId.toString()));

        // Verify TeamManageSchedule rejects cross-organization template
        TeamManageSchedule scheduleCheck = new TeamManageSchedule();
        ReflectionTestUtils.setField(scheduleCheck, "workspaceCheckSupport", workspaceCheckSupport);
        Schedule schedule = new Schedule();
        schedule.setWorkspace(workspaceA);
        schedule.setTemplateReference(templateId.toString());

        when(templateRepository.findById(templateId)).thenReturn(Optional.of(templateB));
        assertFalse(scheduleCheck.ok(schedule, requestScope, Optional.empty()));

        // Verify TeamManageWebhookEvent rejects cross-organization template
        TeamManageWebhookEvent webhookEventCheck = new TeamManageWebhookEvent();
        ReflectionTestUtils.setField(webhookEventCheck, "workspaceCheckSupport", workspaceCheckSupport);
        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspaceA);
        WebhookEvent event = new WebhookEvent();
        event.setWebhook(webhook);
        event.setTemplateId(templateId.toString());

        assertFalse(webhookEventCheck.ok(event, requestScope, Optional.empty()));

        // On UPDATE, non-template edits (e.g. cron, branch) succeed without checking template existence
        when(authenticatedUser.isSuperUser(user)).thenReturn(true);
        ChangeSpec cronChange = new ChangeSpec(null, "cron", "0 0 * * *", "0 0 12 * * ?");
        assertTrue(scheduleCheck.ok(schedule, requestScope, Optional.of(cronChange)));

        ChangeSpec branchChange = new ChangeSpec(null, "branch", "main", "develop");
        assertTrue(webhookEventCheck.ok(event, requestScope, Optional.of(branchChange)));

        // On UPDATE, changing templateReference to foreign template is rejected
        ChangeSpec templateChange = new ChangeSpec(null, "templateReference", null, templateId.toString());
        assertFalse(scheduleCheck.ok(schedule, requestScope, Optional.of(templateChange)));

        // DELETE checks allow removal even when template is missing/dangling
        TeamDeleteSchedule deleteScheduleCheck = new TeamDeleteSchedule();
        ReflectionTestUtils.setField(deleteScheduleCheck, "workspaceCheckSupport", workspaceCheckSupport);
        assertTrue(deleteScheduleCheck.ok(schedule, requestScope, Optional.empty()));

        TeamDeleteWebhookEvent deleteWebhookEventCheck = new TeamDeleteWebhookEvent();
        ReflectionTestUtils.setField(deleteWebhookEventCheck, "workspaceCheckSupport", workspaceCheckSupport);
        assertTrue(deleteWebhookEventCheck.ok(event, requestScope, Optional.empty()));
    }

    @Test
    void testJobWorkspaceOrganizationValidation() {
        UUID orgAId = UUID.randomUUID();
        UUID orgBId = UUID.randomUUID();

        Organization orgA = new Organization();
        orgA.setId(orgAId);
        Team teamA = new Team();
        teamA.setName("team-a");
        orgA.setTeam(List.of(teamA));

        Organization orgB = new Organization();
        orgB.setId(orgBId);

        Workspace workspaceA = new Workspace();
        workspaceA.setId(UUID.randomUUID());
        workspaceA.setOrganization(orgA);

        Access access = new Access();
        access.setName("access-team");
        workspaceA.setAccess(List.of(access));

        Project project = new Project();
        project.setId(UUID.randomUUID());
        ProjectAccess projectAccess = new ProjectAccess();
        projectAccess.setName("proj-team");
        project.setProjectAccess(List.of(projectAccess));
        workspaceA.setProject(project);

        Workspace workspaceB = new Workspace();
        workspaceB.setId(UUID.randomUUID());
        workspaceB.setOrganization(orgB);

        // Job pointing to Org A as its organization, but Workspace B (in Org B)
        Job crossOrgJob = new Job();
        crossOrgJob.setId(999);
        crossOrgJob.setOrganization(orgA);
        crossOrgJob.setWorkspace(workspaceB);

        // Job pointing to Org A as its organization, and Workspace A (in Org A)
        Job validJob = new Job();
        validJob.setId(1000);
        validJob.setOrganization(orgA);
        validJob.setWorkspace(workspaceA);

        // Job with null workspace
        Job nullWsJob = new Job();
        nullWsJob.setId(1001);
        nullWsJob.setOrganization(orgA);

        // Job with null organization
        Job nullOrgJob = new Job();
        nullOrgJob.setId(1002);
        nullOrgJob.setWorkspace(workspaceA);

        // Workspace with null organization
        Workspace nullWsOrg = new Workspace();
        nullWsOrg.setId(UUID.randomUUID());
        Job nullWsOrgJob = new Job();
        nullWsOrgJob.setId(1003);
        nullWsOrgJob.setOrganization(orgA);
        nullWsOrgJob.setWorkspace(nullWsOrg);

        TeamPlanJob planJob = new TeamPlanJob();
        ReflectionTestUtils.setField(planJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(planJob, "groupService", groupService);
        ReflectionTestUtils.setField(planJob, "rbacService", rbacService);
        ReflectionTestUtils.setField(planJob, "workspaceCheckSupport", workspaceCheckSupport);

        TeamManageJob manageJob = new TeamManageJob();
        ReflectionTestUtils.setField(manageJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(manageJob, "groupService", groupService);
        ReflectionTestUtils.setField(manageJob, "rbacService", rbacService);

        TeamApproveJobRbac approveJob = new TeamApproveJobRbac();
        ReflectionTestUtils.setField(approveJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(approveJob, "groupService", groupService);
        ReflectionTestUtils.setField(approveJob, "rbacService", rbacService);

        TeamViewJob viewJob = new TeamViewJob();
        ReflectionTestUtils.setField(viewJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(viewJob, "membershipService", membershipService);

        TeamLimitedPlanJob limitedPlanJob = new TeamLimitedPlanJob();
        ReflectionTestUtils.setField(limitedPlanJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(limitedPlanJob, "groupService", groupService);
        ReflectionTestUtils.setField(limitedPlanJob, "rbacService", rbacService);
        ReflectionTestUtils.setField(limitedPlanJob, "workspaceCheckSupport", workspaceCheckSupport);

        TeamLimitedManageJob limitedManageJob = new TeamLimitedManageJob();
        ReflectionTestUtils.setField(limitedManageJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(limitedManageJob, "groupService", groupService);
        ReflectionTestUtils.setField(limitedManageJob, "rbacService", rbacService);

        TeamLimitedApproveJob limitedApproveJob = new TeamLimitedApproveJob();
        ReflectionTestUtils.setField(limitedApproveJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(limitedApproveJob, "groupService", groupService);
        ReflectionTestUtils.setField(limitedApproveJob, "rbacService", rbacService);

        TeamLimitedViewJob limitedViewJob = new TeamLimitedViewJob();
        ReflectionTestUtils.setField(limitedViewJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(limitedViewJob, "membershipService", membershipService);

        TeamProjectLimitedPlanJob projectLimitedPlanJob = new TeamProjectLimitedPlanJob();
        ReflectionTestUtils.setField(projectLimitedPlanJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(projectLimitedPlanJob, "rbacService", rbacService);
        ReflectionTestUtils.setField(projectLimitedPlanJob, "membershipService", membershipService);
        ReflectionTestUtils.setField(projectLimitedPlanJob, "workspaceCheckSupport", workspaceCheckSupport);

        TeamProjectLimitedManageJob projectLimitedManageJob = new TeamProjectLimitedManageJob();
        ReflectionTestUtils.setField(projectLimitedManageJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(projectLimitedManageJob, "rbacService", rbacService);
        ReflectionTestUtils.setField(projectLimitedManageJob, "membershipService", membershipService);

        TeamProjectLimitedApproveJob projectLimitedApproveJob = new TeamProjectLimitedApproveJob();
        ReflectionTestUtils.setField(projectLimitedApproveJob, "authenticatedUser", authenticatedUser);
        ReflectionTestUtils.setField(projectLimitedApproveJob, "rbacService", rbacService);
        ReflectionTestUtils.setField(projectLimitedApproveJob, "membershipService", membershipService);

        TeamProjectLimitedViewJob projectLimitedViewJob = new TeamProjectLimitedViewJob();
        ReflectionTestUtils.setField(projectLimitedViewJob, "membershipService", membershipService);

        List<com.yahoo.elide.core.security.checks.OperationCheck<Job>> allJobChecks = List.of(
                planJob, manageJob, approveJob, viewJob,
                limitedPlanJob, limitedManageJob, limitedApproveJob, limitedViewJob,
                projectLimitedPlanJob, projectLimitedManageJob, projectLimitedApproveJob, projectLimitedViewJob
        );

        // 1. Null job must not throw NPE and must return false for all 12 checks
        for (var check : allJobChecks) {
            assertFalse(check.ok(null, requestScope, Optional.empty()), "Check " + check.getClass().getSimpleName() + " should reject null job");
        }

        // 2. Null workspace must return false for all 12 checks
        for (var check : allJobChecks) {
            assertFalse(check.ok(nullWsJob, requestScope, Optional.empty()), "Check " + check.getClass().getSimpleName() + " should reject null workspace");
        }

        // 3. Null organization must return false for all 12 checks
        for (var check : allJobChecks) {
            assertFalse(check.ok(nullOrgJob, requestScope, Optional.empty()), "Check " + check.getClass().getSimpleName() + " should reject null organization");
        }

        // 4. Null workspace organization must return false for all 12 checks
        for (var check : allJobChecks) {
            assertFalse(check.ok(nullWsOrgJob, requestScope, Optional.empty()), "Check " + check.getClass().getSimpleName() + " should reject null workspace organization");
        }

        // 5. Cross-organization mismatch must return false for all 12 checks
        for (var check : allJobChecks) {
            assertFalse(check.ok(crossOrgJob, requestScope, Optional.empty()), "Check " + check.getClass().getSimpleName() + " should reject cross-org job");
        }

        // 6. Matching organization: verify positive authorizations
        when(authenticatedUser.isServiceAccount(user)).thenReturn(false);
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(groupService.isMember(user, "team-a")).thenReturn(true);
        when(rbacService.canPlanJob(teamA)).thenReturn(true);
        when(rbacService.canManageJob(teamA)).thenReturn(true);
        when(rbacService.canApproveJob(teamA)).thenReturn(true);
        when(membershipService.checkMembership(user, List.of(teamA))).thenReturn(true);
        Optional<ChangeSpec> approvalTransition = Optional.of(
                new ChangeSpec(null, "status", JobStatus.waitingApproval, JobStatus.approved));

        assertTrue(planJob.ok(validJob, requestScope, Optional.empty()));
        assertTrue(manageJob.ok(validJob, requestScope, Optional.empty()));
        assertTrue(approveJob.ok(validJob, requestScope, approvalTransition));
        assertTrue(viewJob.ok(validJob, requestScope, Optional.empty()));

        when(groupService.isMember(user, "access-team")).thenReturn(true);
        when(rbacService.canPlanJob(access)).thenReturn(true);
        when(rbacService.canManageJob(access)).thenReturn(true);
        when(rbacService.canApproveJob(access)).thenReturn(true);
        when(membershipService.checkLimitedMembership(user, List.of(access))).thenReturn(true);

        assertTrue(limitedPlanJob.ok(validJob, requestScope, Optional.empty()));
        assertTrue(limitedManageJob.ok(validJob, requestScope, Optional.empty()));
        assertTrue(limitedApproveJob.ok(validJob, requestScope, approvalTransition));
        assertTrue(limitedViewJob.ok(validJob, requestScope, Optional.empty()));

        when(membershipService.checkProjectMembership(eq(user), eq(List.of(projectAccess)), any())).thenReturn(true);
        assertTrue(projectLimitedPlanJob.ok(validJob, requestScope, Optional.empty()));
        assertTrue(projectLimitedManageJob.ok(validJob, requestScope, Optional.empty()));
        assertTrue(projectLimitedApproveJob.ok(validJob, requestScope, approvalTransition));
        assertTrue(projectLimitedViewJob.ok(validJob, requestScope, Optional.empty()));
    }

    @Test
    void testSensitiveUpdateChecks() {
        UpdateVariableSecret varCheck = new UpdateVariableSecret();
        UpdateCollectionSecret itemCheck = new UpdateCollectionSecret();

        ChangeSpec changeTrueToFalse = new ChangeSpec(null, "sensitive", true, false);
        ChangeSpec changeFalseToTrue = new ChangeSpec(null, "sensitive", false, true);
        ChangeSpec unchangedTrue = new ChangeSpec(null, "sensitive", true, true);
        ChangeSpec unchangedFalse = new ChangeSpec(null, "sensitive", false, false);

        // Empty changeSpec allows update
        assertTrue(varCheck.ok(new Variable(), requestScope, Optional.empty()));
        assertTrue(itemCheck.ok(new Item(), requestScope, Optional.empty()));

        // Flipping true -> false must be rejected
        assertFalse(varCheck.ok(new Variable(), requestScope, Optional.of(changeTrueToFalse)));
        assertFalse(itemCheck.ok(new Item(), requestScope, Optional.of(changeTrueToFalse)));

        // Flipping false -> true (hardening) must be allowed
        assertTrue(varCheck.ok(new Variable(), requestScope, Optional.of(changeFalseToTrue)));
        assertTrue(itemCheck.ok(new Item(), requestScope, Optional.of(changeFalseToTrue)));

        // Keeping sensitive unchanged must succeed
        assertTrue(varCheck.ok(new Variable(), requestScope, Optional.of(unchangedTrue)));
        assertTrue(itemCheck.ok(new Item(), requestScope, Optional.of(unchangedTrue)));

        assertTrue(varCheck.ok(new Variable(), requestScope, Optional.of(unchangedFalse)));
        assertTrue(itemCheck.ok(new Item(), requestScope, Optional.of(unchangedFalse)));
    }

    @Test
    void testWorkspaceManageHookUpdateConflictAndCoTenancy() throws NoSuchFieldException {
        // Class-level hook has CREATE only (UPDATE moved to field level)
        LifeCycleHookBinding[] classHooks = Workspace.class.getAnnotationsByType(LifeCycleHookBinding.class);
        for (LifeCycleHookBinding hookBinding : classHooks) {
            org.junit.jupiter.api.Assertions.assertNotEquals(LifeCycleHookBinding.Operation.UPDATE, hookBinding.operation());
        }

        // Field-level hooks for UPDATE PRECOMMIT
        org.junit.jupiter.api.Assertions.assertNotNull(
                Workspace.class.getDeclaredField("name").getAnnotation(LifeCycleHookBinding.class));
        org.junit.jupiter.api.Assertions.assertNotNull(
                Workspace.class.getDeclaredField("agent").getAnnotation(LifeCycleHookBinding.class));
        org.junit.jupiter.api.Assertions.assertNotNull(
                Workspace.class.getDeclaredField("vcs").getAnnotation(LifeCycleHookBinding.class));
        org.junit.jupiter.api.Assertions.assertNotNull(
                Workspace.class.getDeclaredField("ssh").getAnnotation(LifeCycleHookBinding.class));
        org.junit.jupiter.api.Assertions.assertNotNull(
                Workspace.class.getDeclaredField("project").getAnnotation(LifeCycleHookBinding.class));
        org.junit.jupiter.api.Assertions.assertNotNull(
                Workspace.class.getDeclaredField("deleted").getAnnotation(LifeCycleHookBinding.class));

        WorkspaceRepository wsRepo = mock(WorkspaceRepository.class);
        WorkspaceManageHook hook = new WorkspaceManageHook(null, null, null, null, wsRepo);

        Organization orgA = new Organization();
        orgA.setId(UUID.randomUUID());
        orgA.setName("OrgA");

        Workspace ws1 = new Workspace();
        ws1.setId(UUID.randomUUID());
        ws1.setName("ws1");
        ws1.setOrganization(orgA);

        Workspace ws2 = new Workspace();
        ws2.setId(UUID.randomUUID());
        ws2.setName("ws2");
        ws2.setOrganization(orgA);

        // 1. Name change without conflict -> succeeds
        ChangeSpec nameChangeNoConflict = new ChangeSpec(null, "name", "old", "ws1");
        when(wsRepo.findAllByOrganizationNameAndName("OrgA", "ws1")).thenReturn(List.of(ws1));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT, ws1, requestScope, Optional.of(nameChangeNoConflict)));

        // 2. Name change with duplicate conflict -> throws SC_CONFLICT (409)
        ChangeSpec nameChangeConflict = new ChangeSpec(null, "name", "old", "ws2");
        ws1.setName("ws2");
        when(wsRepo.findAllByOrganizationNameAndName("OrgA", "ws2")).thenReturn(List.of(ws2));
        org.junit.jupiter.api.Assertions.assertThrows(WorkspaceManagementException.class, () ->
                hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT, ws1, requestScope, Optional.of(nameChangeConflict)));

        // 3. Agent change with cross-org agent -> throws SC_FORBIDDEN (403)
        Organization orgB = new Organization();
        orgB.setId(UUID.randomUUID());
        Agent agentB = new Agent();
        agentB.setId(UUID.randomUUID());
        agentB.setOrganization(orgB);
        ws1.setAgent(agentB);

        ChangeSpec agentChange = new ChangeSpec(null, "agent", null, agentB);
        org.junit.jupiter.api.Assertions.assertThrows(WorkspaceManagementException.class, () ->
                hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT, ws1, requestScope, Optional.of(agentChange)));

        // 4. Same-org relationships -> succeeds
        Agent agentA = new Agent();
        agentA.setId(UUID.randomUUID());
        agentA.setOrganization(orgA);
        ws1.setAgent(agentA);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT, ws1, requestScope, Optional.of(agentChange)));

        // 5. Unrelated field change (e.g. description) does not run name query or relationship checks
        ChangeSpec descChange = new ChangeSpec(null, "description", "old", "new");
        ws1.setAgent(agentB); // Even if cross-org agent was present, non-relationship update doesn't re-validate
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                hook.execute(LifeCycleHookBinding.Operation.UPDATE, LifeCycleHookBinding.TransactionPhase.PRECOMMIT, ws1, requestScope, Optional.of(descChange)));
    }
}
