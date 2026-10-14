package io.terrakube.api;

import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.team.Team;

import java.util.UUID;

/**
 * Teams created for the cascade operation tests. They must not rely on the seeded teams: the test classes
 * share one in-memory database, and other classes (TfcApiTests) rewrite the seeded teams' permissions, so
 * a test that expected manage job on a seeded team only passed when it happened to run first.
 */
final class CascadeOperationTeams {

    /** Can plan and approve, so "manage job" in the organization. */
    static final String OPERATORS = "CASCADE_TEST_OPERATORS";
    /** Read only: no manage job. */
    static final String VIEWERS = "CASCADE_TEST_VIEWERS";

    private CascadeOperationTeams() {
    }

    static void create(TeamRepository teams, OrganizationRepository organizations, UUID organizationId) {
        Organization organization = organizations.findById(organizationId).orElseThrow();
        teams.save(team(organization, OPERATORS, "custom", true));
        teams.save(team(organization, VIEWERS, "read", false));
    }

    static void delete(TeamRepository teams, UUID organizationId) {
        teams.findAllByOrganizationIdAndNameIn(organizationId, java.util.List.of(OPERATORS, VIEWERS)).forEach(teams::delete);
    }

    // Every permission attribute is set explicitly, as the RBAC conventions require.
    private static Team team(Organization organization, String name, String role, boolean jobs) {
        Team team = new Team();
        team.setOrganization(organization);
        team.setName(name);
        team.setRole(role);
        team.setManageState(false);
        team.setManageCollection(false);
        team.setManageWorkspace(false);
        team.setManageModule(false);
        team.setManageProvider(false);
        team.setManageVcs(false);
        team.setManageTemplate(false);
        team.setManagePolicies(false);
        team.setManageJob(jobs);
        team.setPlanJob(jobs);
        team.setApproveJob(jobs);
        return team;
    }
}
