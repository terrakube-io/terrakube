package io.terrakube.api.rs.workspace;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.hooks.workspace.WorkspaceManageHook;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
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
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;

import java.sql.Types;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "team view workspace OR team limited view workspace")
@CreatePermission(expression = "team manage workspace")
@UpdatePermission(expression = "team manage workspace OR team limited manage workspace")
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
    @Convert(converter = IdConverter.class)
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
    private String executionMode;

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
    @UpdatePermission(expression = "team view workspace OR team limited view workspace")
    private List<Job> job;

    @Exclude
    @OneToMany(mappedBy = "workspace")
    private List<Content> content;

    @OneToMany(mappedBy = "workspace")
    private List<WorkspaceTag> workspaceTag;

    @OneToOne
    private Project project;

    @ManyToOne
    private Vcs vcs;

    @OneToOne
    private Ssh ssh;

    @OneToOne
    private Agent agent;
    
    @OneToOne(mappedBy = "workspace", fetch = FetchType.LAZY)
    private Webhook webhook;

    @OneToMany(mappedBy = "workspace", fetch = FetchType.LAZY)
    private List<Reference> reference;

    @OneToMany(mappedBy = "workspace")
    @UpdatePermission(expression = "user is a superuser")
    private List<Access> access;
}
