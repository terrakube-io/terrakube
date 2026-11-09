package io.terrakube.api.rs.project;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.access.ProjectAccess;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Read-only catalogue row for a project: its identity plus a roll-up of its workspaces, computed on read from the
 * status columns that already live on {@code workspace}. Nothing is stored, so it cannot drift from the workspaces.
 *
 * <p>It is a separate entity, with the name and organization repeated as own columns, because Elide rejects sorting
 * over a relationship whenever the read filter also filters over a to-many relationship. Every sort key here
 * ({@code name}, {@code attentionCount}, {@code lastRunAt}, ...) is an own column, so the list can be paged and sorted
 * on the server for any number of projects.
 *
 * <p>A project the caller can see has every one of its workspaces readable by them, so the counts never include a
 * workspace the caller cannot read (see {@code ProjectVisibility}).
 *
 * <p>The SQL avoids boolean literals and dialect functions so it runs unchanged on H2, PostgreSQL, MySQL,
 * SQL Server and Oracle. Soft-deleted workspaces are excluded, matching {@code Workspace}.
 */
@Include
@Entity(name = "project_summary")
@Immutable
@Synchronize({"project", "workspace", "project_access", "job"})
@Subselect("""
        select p.id as id,
               p.name as name,
               p.description as description,
               p.organization_id as organization_id,
               count(w.id) as workspace_count,
               coalesce(sum(case when w.last_job_status in ('completed', 'noChanges') then 1 else 0 end), 0) as completed_count,
               coalesce(sum(case when w.last_job_status = 'failed' then 1 else 0 end), 0) as failed_count,
               coalesce(sum(case when w.last_job_status = 'running' then 1 else 0 end), 0) as running_count,
               coalesce(sum(case when w.last_job_status in ('pending', 'approved', 'queue') then 1 else 0 end), 0) as queued_count,
               coalesce(sum(case when w.last_job_status = 'waitingApproval' then 1 else 0 end), 0) as waiting_approval_count,
               coalesce(sum(case when w.last_job_status = 'NeverExecuted' then 1 else 0 end), 0) as never_run_count,
               coalesce(sum(case when w.policy_compliance_status = 'NON_COMPLIANT' then 1 else 0 end), 0) as non_compliant_count,
               coalesce(sum(case when w.policy_compliance_status = 'NON_COMPLIANT' and w.policy_hard_violations > 0 then 1 else 0 end), 0) as hard_violation_count,
               coalesce(sum(case when w.policy_compliance_status = 'NON_COMPLIANT'
                                   and (w.policy_hard_violations is null or w.policy_hard_violations = 0)
                                   and w.policy_soft_violations > 0 then 1 else 0 end), 0) as soft_violation_count,
               coalesce(sum(case when w.policy_compliance_status = 'COMPLIANT' then 1 else 0 end), 0) as compliant_count,
               coalesce(sum(case when w.policy_compliance_status = 'EXEMPTED' then 1 else 0 end), 0) as exempted_count,
               coalesce(sum(case when cast(w.locked as integer) = 1 then 1 else 0 end), 0) as locked_count,
               coalesce(sum(case when w.last_job_status in ('failed', 'waitingApproval')
                                   or w.policy_compliance_status = 'NON_COMPLIANT' then 1 else 0 end), 0) as attention_count,
               max(w.last_job_date) as last_run_at,
               coalesce(max(q.queued_job_count), 0) as queued_job_count,
               max(q.queued_since) as queued_since,
               (select count(*) from project_access pa where pa.project_id = p.id) as team_count
        from project p
        left join workspace w on w.organization_id = p.organization_id and w.project_id = p.id
                                 and cast(w.deleted as integer) = 0
        left join (select qw.organization_id as organization_id,
                          qw.project_id as project_id,
                          count(*) as queued_job_count,
                          min(coalesce(qj.queued_at, qj.created_date)) as queued_since
                     from job qj join workspace qw on qw.id = qj.workspace_id
                    where cast(qw.deleted as integer) = 0 and qj.status in ('pending', 'approved')
                    group by qw.organization_id, qw.project_id) q
               on q.organization_id = p.organization_id and q.project_id = p.id
        group by p.id, p.name, p.description, p.organization_id
        """)
@ReadPermission(expression = "user is a superuser OR project summary read filter")
@CreatePermission(expression = "NONE")
@UpdatePermission(expression = "NONE")
@DeletePermission(expression = "NONE")
@Getter
@Setter
public class ProjectSummary {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Column(name = "id")
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    /** The owning organization's id as a plain column, so callers can filter by organization without reading it. */
    @JdbcTypeCode(Types.VARCHAR)
    @Column(name = "organization_id", insertable = false, updatable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", insertable = false, updatable = false)
    private Organization organization;

    /** Read-only mapping onto the same rows as {@link Project#getProjectAccess()}, used by the read filter. */
    @BatchSize(size = 100)
    @OneToMany
    @JoinColumn(name = "project_id", referencedColumnName = "id", insertable = false, updatable = false)
    private List<ProjectAccess> projectAccess = new ArrayList<>();

    @Column(name = "workspace_count")
    private Long workspaceCount;

    @Column(name = "completed_count")
    private Long completedCount;

    @Column(name = "failed_count")
    private Long failedCount;

    @Column(name = "running_count")
    private Long runningCount;

    @Column(name = "queued_count")
    private Long queuedCount;

    @Column(name = "waiting_approval_count")
    private Long waitingApprovalCount;

    @Column(name = "never_run_count")
    private Long neverRunCount;

    @Column(name = "non_compliant_count")
    private Long nonCompliantCount;

    /** Non-compliant workspaces by the worst violation of their latest evaluation: any hard, or soft only. */
    @Column(name = "hard_violation_count")
    private Long hardViolationCount;

    @Column(name = "soft_violation_count")
    private Long softViolationCount;

    /** Workspaces whose last policy evaluation passed, and ones where the workspace is exempted. */
    @Column(name = "compliant_count")
    private Long compliantCount;

    @Column(name = "exempted_count")
    private Long exemptedCount;

    @Column(name = "locked_count")
    private Long lockedCount;

    /** Failed, waiting for approval or non-compliant. Locked workspaces are deliberate and counted separately. */
    @Column(name = "attention_count")
    private Long attentionCount;

    @Column(name = "last_run_at")
    private Date lastRunAt;

    /** Jobs still waiting to run, across the project's workspaces. */
    @Column(name = "queued_job_count")
    private Long queuedJobCount;

    /** When the oldest job still waiting to run entered the queue (its queued_at, or created_date for old rows). */
    @Column(name = "queued_since")
    private Date queuedSince;

    @Column(name = "team_count")
    private Long teamCount;
}
