package io.terrakube.api.rs.checks;

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
import io.terrakube.api.rs.checks.globalvar.TeamViewGlobalvar;
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
import io.terrakube.api.rs.checks.webhook.TeamManageWebhook;
import io.terrakube.api.rs.checks.webhook.TeamManageWebhookEvent;
import io.terrakube.api.rs.checks.webhook.TeamViewWebhook;
import io.terrakube.api.rs.checks.webhook.TeamViewWebhookEvent;
import io.terrakube.api.rs.checks.workspace.TeamManageSchedule;
import io.terrakube.api.rs.checks.workspace.TeamManageWorkspaceTag;
import io.terrakube.api.rs.checks.workspace.TeamViewSchedule;
import io.terrakube.api.rs.checks.workspace.TeamViewWorkspaceTag;
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
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
    }
}
