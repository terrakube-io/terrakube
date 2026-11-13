package io.terrakube.api.plugin.security.rbac;

import io.terrakube.api.rs.team.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RbacV2ServiceTest {

    private RbacV2Service rbacService;

    @BeforeEach
    void setUp() {
        rbacService = new RbacV2Service();
    }

    @Test
    void testCanManagePolicies_Admin() {
        Team team = new Team();
        team.setRole("admin");
        team.setManagePolicies(false); // role overrides boolean

        assertTrue(rbacService.canManagePolicies(team));
    }

    @Test
    void testCanManagePolicies_Write() {
        Team team = new Team();
        team.setRole("write");
        team.setManagePolicies(true); // write role cannot manage policies

        assertFalse(rbacService.canManagePolicies(team));
    }

    @Test
    void testCanManagePolicies_PlanAndRead() {
        Team planTeam = new Team();
        planTeam.setRole("plan");
        assertFalse(rbacService.canManagePolicies(planTeam));

        Team readTeam = new Team();
        readTeam.setRole("read");
        assertFalse(rbacService.canManagePolicies(readTeam));
    }

    @Test
    void testCanManagePolicies_Custom() {
        Team customTeamGranted = new Team();
        customTeamGranted.setRole("custom");
        customTeamGranted.setManagePolicies(true);
        assertTrue(rbacService.canManagePolicies(customTeamGranted));

        Team customTeamDenied = new Team();
        customTeamDenied.setRole("custom");
        customTeamDenied.setManagePolicies(false);
        assertFalse(rbacService.canManagePolicies(customTeamDenied));
    }

    @Test
    void testCanManagePolicies_NullRoleFallsBackToCustom() {
        Team defaultTeamGranted = new Team();
        defaultTeamGranted.setRole(null);
        defaultTeamGranted.setManagePolicies(true);
        assertTrue(rbacService.canManagePolicies(defaultTeamGranted));

        Team defaultTeamDenied = new Team();
        defaultTeamDenied.setRole(null);
        defaultTeamDenied.setManagePolicies(false);
        assertFalse(rbacService.canManagePolicies(defaultTeamDenied));
    }

    @Test
    void canDestroyJob_adminAndWriteAlwaysPlanAndReadNever() {
        for (String role : new String[] {"admin", "write"}) {
            Team team = new Team();
            team.setRole(role);
            team.setDestroyJob(false); // the role decides, not the flag
            assertTrue(rbacService.canDestroyJob(team), role);
        }
        for (String role : new String[] {"plan", "read"}) {
            Team team = new Team();
            team.setRole(role);
            team.setDestroyJob(true);
            assertFalse(rbacService.canDestroyJob(team), role);
        }
    }

    @Test
    void canDestroyJob_customFollowsTheFlagAndSoDoesNoRole() {
        Team granted = new Team();
        granted.setRole("custom");
        granted.setDestroyJob(true);
        assertTrue(rbacService.canDestroyJob(granted));

        Team denied = new Team();
        denied.setRole("custom");
        denied.setDestroyJob(false);
        assertFalse(rbacService.canDestroyJob(denied));

        Team noRole = new Team();
        noRole.setDestroyJob(false);
        assertFalse(rbacService.canDestroyJob(noRole));
    }

    @Test
    void canDestroyJob_workspaceAndProjectAccessFollowTheSameRule() {
        io.terrakube.api.rs.workspace.access.Access access = new io.terrakube.api.rs.workspace.access.Access();
        access.setRole("custom");
        access.setDestroyJob(false);
        assertFalse(rbacService.canDestroyJob(access));
        access.setDestroyJob(true);
        assertTrue(rbacService.canDestroyJob(access));
        access.setRole("plan");
        assertFalse(rbacService.canDestroyJob(access));

        io.terrakube.api.rs.project.access.ProjectAccess project = new io.terrakube.api.rs.project.access.ProjectAccess();
        project.setRole("write");
        assertTrue(rbacService.canDestroyJob(project));
        project.setRole("custom");
        project.setDestroyJob(false);
        assertFalse(rbacService.canDestroyJob(project));
    }
}
