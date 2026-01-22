package io.terrakube.api.rs.job;

import java.util.List;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.job.JobManageHook;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.Setter;

@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = JobManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = JobManageHook.class)
@ReadPermission(expression = "team view job OR team limited view job")
@CreatePermission(expression = "team manage job OR team limited manage job")
@UpdatePermission(expression = "team manage job OR team limited manage job OR user is a super service")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "job")
public class Job extends GenericAuditFields {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "comments")
    private String comments;

    @UpdatePermission(expression = "team approve job OR team limited manage job OR team manage job OR user is a super service")
    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.pending;

    @Column(name = "output")
    private String output;

    @Column(name = "commit_id")
    private String commitId;

    @Exclude
    @Column(name = "auto_apply")
    private boolean autoApply = false;

    @Column(name = "terraform_plan")
    private String terraformPlan;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "approval_team")
    private String approvalTeam;

    @Column(name = "tcl")
    private String tcl;

    @Exclude
    @Column(name = "override_source")
    private String overrideSource;

    @Column(name = "override_branch")
    private String overrideBranch;

    @Column(name = "template_reference")
    private String templateReference;

    @Column(name = "via")
    private String via = "UI";

    @Column(name = "refresh")
    private boolean refresh = true;

    @Column(name = "plan_changes")
    private boolean planChanges = true;

    @Column(name = "refresh_only")
    private boolean refreshOnly = false;

    @ManyToOne
    private Organization organization;

    @ManyToOne
    private Workspace workspace;

    @UpdatePermission(expression = "user is a super service")
    @OneToMany(mappedBy = "job")
    private List<Step> step;

    @OneToMany(mappedBy = "job")
    private List<Address> address;

}

