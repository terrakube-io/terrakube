package io.terrakube.api.rs.workspace;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.ExecutionMode;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.hooks.workspace.WorkspaceManageHook;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.content.Content;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import jakarta.persistence.CascadeType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;

import java.sql.Types;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "user is a superuser OR workspace read filter")
@CreatePermission(expression = "team manage workspace OR team project limited create workspace")
@UpdatePermission(expression = "team manage workspace OR team project limited manage workspace OR team limited manage workspace")
@DeletePermission(expression = "team manage workspace")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = WorkspaceManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = WorkspaceManageHook.class)
@Include
@Getter
@Setter
@Entity(name = "workspace")
@SQLRestriction(value = "deleted = false")
public class Workspace extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "source")
    private String source;

    @Column(name = "branch")
    private String branch;

    @Column(name = "folder")
    private String folder;

    @Column(name = "last_job_status")
    private JobStatus lastJobStatus = JobStatus.NeverExecuted;

    @Column(name = "last_job_date")
    private Date lastJobDate;

    @Column(name = "locked")
    private boolean locked;

    @Column(name = "deleted")
    private boolean deleted;

    @Column(name = "allow_remote_apply")
    private boolean allowRemoteApply = false;

    @Column(name = "global_remote_state")
    private boolean globalRemoteState = true;

    @Column(name = "shared_ids")
    private String sharedIds;

    @Column(name = "default_template")
    private String defaultTemplate;

    @Column(name = "lock_description")
    private String lockDescription;

    @Column(name = "iac_type")
    private String iacType = "terraform";

    @Column(name = "module_ssh_key")
    private String moduleSshKey;

    @Column(name = "terraform_version")
    private String terraformVersion;

    @Column(name = "execution_mode")
    @Enumerated(EnumType.STRING)
    private ExecutionMode executionMode;

    @Column(name = "policy_compliance_status")
    @Enumerated(EnumType.STRING)
    private io.terrakube.api.rs.policy.PolicyComplianceStatus policyComplianceStatus = io.terrakube.api.rs.policy.PolicyComplianceStatus.UNKNOWN;

    @ManyToOne
    private Organization organization;

    @OneToMany(mappedBy = "workspace")
    private List<Variable> variable;

    @UpdatePermission(expression = "user is a super service")
    @OneToMany(mappedBy = "workspace")
    private List<History> history;

    @OneToMany(mappedBy = "workspace")
    private List<Schedule> schedule;

    @OneToMany(mappedBy = "workspace")
    @UpdatePermission(expression = "team view workspace OR team project limited view workspace OR team limited view workspace")
    private List<Job> job;

    @Exclude
    @OneToMany(mappedBy = "workspace")
    private List<Content> content;

    @OneToMany(mappedBy = "workspace")
    private List<WorkspaceTag> workspaceTag;

    @UpdatePermission(expression = "team manage workspace OR team project limited reassign workspace")
    @OneToOne
    private Project project;

    @ManyToOne
    private Vcs vcs;

    @OneToOne
    private Ssh ssh;

    @OneToOne
    private Agent agent;
    
    @OneToOne(mappedBy = "workspace", fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private Webhook webhook;

    @OneToMany(mappedBy = "workspace", fetch = FetchType.LAZY)
    private List<NotificationConfiguration> notificationConfiguration;

    @OneToMany(mappedBy = "workspace", fetch = FetchType.LAZY)
    private List<Reference> reference;

    /**
     * Triggers that fire runs on THIS workspace when their source applies.
     *
     * The permission mirrors the one on {@link #job}: edges are created and removed through
     * the runTrigger resource, whose own Create/Update/Delete checks validate both ends, so
     * the inverse side only has to be as guarded as seeing the workspace. Requiring more
     * here does not add protection - Elide maintains the bidirectional relationship when a
     * trigger is created, so a stricter rule on this field is evaluated on the ordinary
     * create path and would lock out every non-superuser regardless of manage rights.
     *
     * No cascade or orphanRemoval: workspaces are soft deleted (see the SQLRestriction on
     * this class), so a cascade would never fire for the case it appears to cover, while
     * orphanRemoval would turn dropping an element from this collection into a row delete
     * that never passes through the trigger's own DeletePermission.
     */
    @UpdatePermission(expression = "team view workspace OR team project limited view workspace OR team limited view workspace")
    @OneToMany(mappedBy = "destinationWorkspace", fetch = FetchType.LAZY)
    private List<WorkspaceRunTrigger> runTriggers;

    /** Triggers where THIS workspace is the source, i.e. the runs it sets off. */
    @UpdatePermission(expression = "team view workspace OR team project limited view workspace OR team limited view workspace")
    @OneToMany(mappedBy = "sourceWorkspace", fetch = FetchType.LAZY)
    private List<WorkspaceRunTrigger> sourceRunTriggers;

    @OneToMany(mappedBy = "workspace")
    @UpdatePermission(expression = "user is a superuser OR team manage workspace OR team workspace admin manages access field")
    private List<Access> access;
}
