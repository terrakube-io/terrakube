package io.terrakube.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.UUID;

import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.ProjectRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.policy.PolicyComplianceStatus;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.ProjectSummary;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.EntityManager;

/**
 * Runs the project summary aggregate on a real database. The aggregate is native SQL (a Hibernate
 * {@code @Subselect}), so the in-memory test database cannot prove it works on PostgreSQL or SQL Server.
 */
final class ProjectSummaryDatabaseCheck {

    private ProjectSummaryDatabaseCheck() {
    }

    static void run(OrganizationRepository organizations, ProjectRepository projects, WorkspaceRepository workspaces,
                    EntityManager entityManager) {
        Organization organization = new Organization();
        organization.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        organization = organizations.save(organization);

        Project project = new Project();
        project.setName("summary-" + UUID.randomUUID().toString().substring(0, 8));
        project.setOrganization(organization);
        project = projects.save(project);

        Project empty = new Project();
        empty.setName("empty-" + UUID.randomUUID().toString().substring(0, 8));
        empty.setOrganization(organization);
        empty = projects.save(empty);

        workspace(workspaces, organization, project, JobStatus.completed, PolicyComplianceStatus.COMPLIANT, false, false);
        workspace(workspaces, organization, project, JobStatus.failed, PolicyComplianceStatus.COMPLIANT, false, false);
        workspace(workspaces, organization, project, JobStatus.waitingApproval, PolicyComplianceStatus.NON_COMPLIANT, false, false);
        workspace(workspaces, organization, project, JobStatus.running, PolicyComplianceStatus.COMPLIANT, true, false);
        workspace(workspaces, organization, project, JobStatus.queue, PolicyComplianceStatus.UNKNOWN, false, false);
        workspace(workspaces, organization, project, JobStatus.NeverExecuted, PolicyComplianceStatus.UNKNOWN, false, false);
        // Soft-deleted workspaces must not be counted.
        workspace(workspaces, organization, project, JobStatus.failed, PolicyComplianceStatus.NON_COMPLIANT, false, true);
        entityManager.flush();
        entityManager.clear();

        ProjectSummary summary = entityManager
                .createQuery("select s from project_summary s where s.id = :id", ProjectSummary.class)
                .setParameter("id", project.getId())
                .getSingleResult();

        assertThat(summary.getName()).isEqualTo(project.getName());
        assertThat(summary.getOrganizationId()).isEqualTo(organization.getId());
        assertThat(summary.getWorkspaceCount()).isEqualTo(6L);
        assertThat(summary.getCompletedCount()).isEqualTo(1L);
        assertThat(summary.getFailedCount()).isEqualTo(1L);
        assertThat(summary.getWaitingApprovalCount()).isEqualTo(1L);
        assertThat(summary.getRunningCount()).isEqualTo(1L);
        assertThat(summary.getQueuedCount()).isEqualTo(1L);
        assertThat(summary.getNeverRunCount()).isEqualTo(1L);
        assertThat(summary.getNonCompliantCount()).isEqualTo(1L);
        assertThat(summary.getLockedCount()).isEqualTo(1L);
        assertThat(summary.getAttentionCount()).isEqualTo(2L);
        assertThat(summary.getTeamCount()).isZero();
        assertThat(summary.getLastRunAt()).isNotNull();

        // Sorting on an aggregate column and paging work in the same statement.
        var ordered = entityManager
                .createQuery("select s.name from project_summary s where s.organizationId = :org "
                        + "order by s.attentionCount desc, s.name", String.class)
                .setParameter("org", organization.getId())
                .setMaxResults(1)
                .getResultList();
        assertThat(ordered).containsExactly(project.getName());

        // A project that never ran sorts last in both directions, on every database: hasRun comes first in the sort.
        for (String direction : new String[]{"asc", "desc"}) {
            var byLastRun = entityManager
                    .createQuery("select s.name from project_summary s where s.organizationId = :org "
                            + "order by s.hasRun desc, s.lastRunAt " + direction + ", s.name", String.class)
                    .setParameter("org", organization.getId())
                    .getResultList();
            assertThat(byLastRun).containsExactly(project.getName(), empty.getName());
        }

        ProjectSummary none = entityManager
                .createQuery("select s from project_summary s where s.id = :id", ProjectSummary.class)
                .setParameter("id", empty.getId())
                .getSingleResult();
        assertThat(none.getWorkspaceCount()).isZero();
        assertThat(none.getAttentionCount()).isZero();
        assertThat(none.getLastRunAt()).isNull();
    }

    private static void workspace(WorkspaceRepository workspaces, Organization organization, Project project,
                                  JobStatus status, PolicyComplianceStatus policy, boolean locked, boolean deleted) {
        Workspace workspace = new Workspace();
        workspace.setName("ws-" + UUID.randomUUID());
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.6.0");
        workspace.setOrganization(organization);
        workspace.setProject(project);
        workspace.setLastJobStatus(status);
        workspace.setPolicyComplianceStatus(policy);
        workspace.setLocked(locked);
        workspace.setDeleted(deleted);
        workspace.setLastJobDate(new Date());
        workspaces.save(workspace);
    }
}
