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
}
