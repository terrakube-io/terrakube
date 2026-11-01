package io.terrakube.api.rs.checks.job;

import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobApprovalSecurityChecksTest {

    private AuthenticatedUser authenticatedUser;
    private GroupService groupService;
    private RbacService rbacService;
    private MembershipService membershipService;
    private RequestScope requestScope;
    private User user;

    @BeforeEach
    void setUp() {
        authenticatedUser = mock(AuthenticatedUser.class);
        groupService = mock(GroupService.class);
        rbacService = mock(RbacService.class);
        membershipService = mock(MembershipService.class);
        requestScope = mock(RequestScope.class);
        user = mock(User.class);
        when(requestScope.getUser()).thenReturn(user);
    }

    @ParameterizedTest
    @MethodSource("approvalTransitions")
    void approvalTransitionOnlyAllowsStatusChangesFromWaitingApproval(
            String fieldName, JobStatus original, JobStatus modified, boolean expected) {
        Optional<ChangeSpec> change = Optional.of(new ChangeSpec(null, fieldName, original, modified));

        assertEquals(expected, TeamApproveJob.isApprovalTransition(change));
    }

    @Test
    void approvalTransitionRequiresAChange() {
        assertFalse(TeamApproveJob.isApprovalTransition(Optional.empty()));
    }

    @ParameterizedTest
    @MethodSource("cancellationTransitions")
    void cancellationTransitionOnlyAllowsNonTerminalJobs(JobStatus original, boolean expected) {
        assertEquals(expected, TeamApproveJob.isCancellationTransition(
                change(original, JobStatus.cancelled)));
    }

    @Test
    void organizationRbacRequiresValidApprovalTransition() {
        Team team = new Team();
        team.setName("organization-approvers");
        Organization organization = organization();
        organization.setTeam(List.of(team));
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization);
        Job job = jobIn(workspace);

        TeamApproveJobRbac check = new TeamApproveJobRbac();
        check.authenticatedUser = authenticatedUser;
        check.groupService = groupService;
        check.rbacService = rbacService;

        when(groupService.isMember(user, team.getName())).thenReturn(true);
        when(rbacService.canApproveJob(team)).thenReturn(true);

        assertTrue(check.ok(job, requestScope, change(JobStatus.waitingApproval, JobStatus.approved)));
        assertTrue(check.ok(job, requestScope, change(JobStatus.running, JobStatus.cancelled)));
        assertFalse(check.ok(job, requestScope, change(JobStatus.waitingApproval, JobStatus.failed)));
    }

    @Test
    void organizationRbacDoesNotAllowPlanOnlyUsersToCancel() {
        Team team = new Team();
        team.setName("organization-planners");
        Organization organization = organization();
        organization.setTeam(List.of(team));
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization);
        Job job = jobIn(workspace);

        TeamApproveJobRbac check = new TeamApproveJobRbac();
        check.authenticatedUser = authenticatedUser;
        check.groupService = groupService;
        check.rbacService = rbacService;

        when(groupService.isMember(user, team.getName())).thenReturn(true);
        when(rbacService.canApproveJob(team)).thenReturn(false);

        assertFalse(check.ok(job, requestScope, change(JobStatus.pending, JobStatus.cancelled)));
    }

    @Test
    void workspaceRbacRequiresValidApprovalTransition() {
        Access access = new Access();
        access.setName("workspace-approvers");
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization());
        workspace.setAccess(List.of(access));
        Job job = jobIn(workspace);

        TeamLimitedApproveJob check = new TeamLimitedApproveJob();
        check.authenticatedUser = authenticatedUser;
        check.groupService = groupService;
        check.rbacService = rbacService;

        when(groupService.isMember(user, access.getName())).thenReturn(true);
        when(rbacService.canApproveJob(access)).thenReturn(true);

        assertTrue(check.ok(job, requestScope, change(JobStatus.waitingApproval, JobStatus.rejected)));
        assertTrue(check.ok(job, requestScope, change(JobStatus.queue, JobStatus.cancelled)));
        assertFalse(check.ok(job, requestScope, change(JobStatus.running, JobStatus.failed)));
    }

    @Test
    void projectRbacRequiresValidApprovalTransition() {
        ProjectAccess access = new ProjectAccess();
        access.setName("project-approvers");
        List<ProjectAccess> accessList = List.of(access);
        Project project = new Project();
        project.setProjectAccess(accessList);
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization());
        workspace.setProject(project);
        Job job = jobIn(workspace);

        TeamProjectLimitedApproveJob check = new TeamProjectLimitedApproveJob();
        check.authenticatedUser = authenticatedUser;
        check.rbacService = rbacService;
        check.membershipService = membershipService;

        when(membershipService.checkProjectMembership(eq(user), eq(accessList), any())).thenReturn(true);

        assertTrue(check.ok(job, requestScope, change(JobStatus.waitingApproval, JobStatus.approved)));
        assertTrue(check.ok(job, requestScope, change(JobStatus.approved, JobStatus.cancelled)));
        assertFalse(check.ok(job, requestScope, change(JobStatus.waitingApproval, JobStatus.completed)));
    }

    @Test
    void invalidTransitionsAreRejectedBeforeMembershipChecks() {
        TeamApproveJobRbac organizationCheck = new TeamApproveJobRbac();
        organizationCheck.authenticatedUser = authenticatedUser;
        organizationCheck.groupService = groupService;
        organizationCheck.rbacService = rbacService;

        assertFalse(organizationCheck.ok(
                new Job(), requestScope, change(JobStatus.failed, JobStatus.approved)));
        verifyNoInteractions(authenticatedUser, groupService, rbacService);
    }

    private static Optional<ChangeSpec> change(JobStatus original, JobStatus modified) {
        return Optional.of(new ChangeSpec(null, "status", original, modified));
    }

    private static Organization organization() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        return organization;
    }

    private static Job jobIn(Workspace workspace) {
        Job job = new Job();
        job.setWorkspace(workspace);
        job.setOrganization(workspace.getOrganization());
        return job;
    }

    private static Stream<Arguments> approvalTransitions() {
        return Stream.of(
                Arguments.of("status", JobStatus.waitingApproval, JobStatus.approved, true),
                Arguments.of("status", JobStatus.waitingApproval, JobStatus.rejected, true),
                Arguments.of("status", JobStatus.failed, JobStatus.approved, false),
                Arguments.of("status", JobStatus.completed, JobStatus.approved, false),
                Arguments.of("status", JobStatus.running, JobStatus.rejected, false),
                Arguments.of("status", JobStatus.waitingApproval, JobStatus.failed, false),
                Arguments.of("output", JobStatus.waitingApproval, JobStatus.approved, false));
    }

    private static Stream<Arguments> cancellationTransitions() {
        return Stream.of(
                Arguments.of(JobStatus.pending, true),
                Arguments.of(JobStatus.queue, true),
                Arguments.of(JobStatus.running, true),
                Arguments.of(JobStatus.waitingApproval, true),
                Arguments.of(JobStatus.approved, true),
                Arguments.of(JobStatus.failed, false),
                Arguments.of(JobStatus.completed, false),
                Arguments.of(JobStatus.noChanges, false),
                Arguments.of(JobStatus.rejected, false),
                Arguments.of(JobStatus.cancelled, false));
    }
}
