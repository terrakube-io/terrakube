package io.terrakube.api.rs.checks.job.step;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class StepSecurityChecksTest {

    private AuthenticatedUser authenticatedUser;
    private MembershipService membershipService;
    private RequestScope requestScope;
    private User user;

    @BeforeEach
    void setUp() {
        authenticatedUser = Mockito.mock(AuthenticatedUser.class);
        membershipService = Mockito.mock(MembershipService.class);
        requestScope = Mockito.mock(RequestScope.class);
        user = Mockito.mock(User.class);
        when(requestScope.getUser()).thenReturn(user);
    }

    @Test
    void testStepEntityAnnotations() {
        Include include = Step.class.getAnnotation(Include.class);
        assertNotNull(include, "Step must have @Include");
        assertFalse(include.rootLevel(), "Step must have rootLevel = false");

        ReadPermission read = Step.class.getAnnotation(ReadPermission.class);
        assertNotNull(read, "Step must have @ReadPermission");
        assertEquals("team view job step OR team project limited view job step OR team limited view job step OR executor read job step OR user is an executor service", read.expression());

        CreatePermission create = Step.class.getAnnotation(CreatePermission.class);
        assertNotNull(create, "Step must have @CreatePermission");
        assertEquals("user is a super service", create.expression());

        UpdatePermission update = Step.class.getAnnotation(UpdatePermission.class);
        assertNotNull(update, "Step must have @UpdatePermission");
        assertEquals("user is a super service OR executor manage job step OR user is an executor service", update.expression());

        DeletePermission delete = Step.class.getAnnotation(DeletePermission.class);
        assertNotNull(delete, "Step must have @DeletePermission");
        assertEquals("user is a super service", delete.expression());
    }

    @Test
    void testTeamViewJobStep_SuperUserAllowed() {
        TeamViewJobStep check = new TeamViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        Organization org = new Organization();
        Job job = new Job();
        job.setOrganization(org);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setJob(job);

        assertTrue(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testTeamViewJobStep_MemberAllowedAndDenied() {
        TeamViewJobStep check = new TeamViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        Team team = new Team();
        team.setName("Devs");
        List<Team> teams = List.of(team);

        Organization org = new Organization();
        org.setTeam(teams);

        Job job = new Job();
        job.setOrganization(org);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setJob(job);

        when(membershipService.checkMembership(user, teams)).thenReturn(true);
        assertTrue(check.ok(step, requestScope, Optional.empty()));

        when(membershipService.checkMembership(user, teams)).thenReturn(false);
        assertFalse(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testTeamViewJobStep_NullJobOrOrgReturnsFalse() {
        TeamViewJobStep check = new TeamViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        Step stepWithoutJob = new Step();
        assertFalse(check.ok(stepWithoutJob, requestScope, Optional.empty()));

        Step stepWithNullOrg = new Step();
        stepWithNullOrg.setJob(new Job());
        assertFalse(check.ok(stepWithNullOrg, requestScope, Optional.empty()));
    }

    @Test
    void testTeamLimitedViewJobStep_SuperUserAllowed() {
        TeamLimitedViewJobStep check = new TeamLimitedViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        Workspace workspace = new Workspace();
        Job job = new Job();
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setJob(job);

        assertTrue(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testTeamLimitedViewJobStep_MemberAllowedAndDenied() {
        TeamLimitedViewJobStep check = new TeamLimitedViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        Access access = new Access();
        access.setName("WorkspaceDevs");
        List<Access> accessList = List.of(access);

        Workspace workspace = new Workspace();
        workspace.setAccess(accessList);

        Job job = new Job();
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setJob(job);

        when(membershipService.checkLimitedMembership(user, accessList)).thenReturn(true);
        assertTrue(check.ok(step, requestScope, Optional.empty()));

        when(membershipService.checkLimitedMembership(user, accessList)).thenReturn(false);
        assertFalse(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testTeamLimitedViewJobStep_NullJobOrWorkspaceReturnsFalse() {
        TeamLimitedViewJobStep check = new TeamLimitedViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        Step stepWithoutJob = new Step();
        assertFalse(check.ok(stepWithoutJob, requestScope, Optional.empty()));

        Step stepWithNullWorkspace = new Step();
        stepWithNullWorkspace.setJob(new Job());
        assertFalse(check.ok(stepWithNullWorkspace, requestScope, Optional.empty()));
    }

    @Test
    void testTeamProjectLimitedViewJobStep_SuperUserAllowed() {
        TeamProjectLimitedViewJobStep check = new TeamProjectLimitedViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        Workspace workspace = new Workspace();
        Job job = new Job();
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setJob(job);

        assertTrue(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testTeamProjectLimitedViewJobStep_MemberAllowedAndDenied() {
        TeamProjectLimitedViewJobStep check = new TeamProjectLimitedViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        ProjectAccess pa = new ProjectAccess();
        pa.setName("ProjectTeam");
        List<ProjectAccess> paList = List.of(pa);

        Project project = new Project();
        project.setProjectAccess(paList);

        Workspace workspace = new Workspace();
        workspace.setProject(project);

        Job job = new Job();
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setJob(job);

        when(membershipService.checkProjectMembership(eq(user), eq(paList), any())).thenReturn(true);
        assertTrue(check.ok(step, requestScope, Optional.empty()));

        when(membershipService.checkProjectMembership(eq(user), eq(paList), any())).thenReturn(false);
        assertFalse(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testTeamProjectLimitedViewJobStep_NullSafety() {
        TeamProjectLimitedViewJobStep check = new TeamProjectLimitedViewJobStep();
        check.authenticatedUser = authenticatedUser;
        check.membershipService = membershipService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        Step stepWithoutJob = new Step();
        assertFalse(check.ok(stepWithoutJob, requestScope, Optional.empty()));

        Step stepWithNullWorkspace = new Step();
        stepWithNullWorkspace.setJob(new Job());
        assertFalse(check.ok(stepWithNullWorkspace, requestScope, Optional.empty()));

        Workspace wsNullProject = new Workspace();
        Job jobNullProject = new Job();
        jobNullProject.setWorkspace(wsNullProject);
        Step stepNullProject = new Step();
        stepNullProject.setJob(jobNullProject);
        assertFalse(check.ok(stepNullProject, requestScope, Optional.empty()));

        Project projectEmptyAccess = new Project();
        projectEmptyAccess.setProjectAccess(List.of());
        Workspace wsEmptyAccess = new Workspace();
        wsEmptyAccess.setProject(projectEmptyAccess);
        Job jobEmptyAccess = new Job();
        jobEmptyAccess.setWorkspace(wsEmptyAccess);
        Step stepEmptyAccess = new Step();
        stepEmptyAccess.setJob(jobEmptyAccess);
        assertFalse(check.ok(stepEmptyAccess, requestScope, Optional.empty()));
    }
}
