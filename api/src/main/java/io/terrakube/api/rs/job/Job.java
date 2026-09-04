package io.terrakube.api.rs.job;

import java.util.List;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.job.JobManageHook;
import io.terrakube.api.rs.hooks.notification.JobNotificationHook;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;

import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
@ReadPermission(expression = "team view job OR team project limited view job OR team limited view job")
@CreatePermission(expression = "team manage job OR team limited manage job OR team plan job OR team limited plan job OR team project limited plan job")
@UpdatePermission(expression = "team manage job OR team limited manage job OR team project limited manage job OR user is a super service")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "job")
@SQLRestriction(value = "deleted = false")
public class Job extends GenericAuditFields {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "comments")
    private String comments;

    @UpdatePermission(expression = "team approve job OR team approve job rbac OR team limited approve job OR team project limited approve job OR user is a super service")
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = JobNotificationHook.class)
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = JobNotificationHook.class)
    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.pending;

    @Column(name = "output")
    private String output;

    @Column(name = "commit_id")
    private String commitId;

    @Exclude
    @Column(name = "auto_apply")
    private boolean autoApply = false;

    @Exclude
    @Column(name = "deleted")
    private boolean deleted = false;

    @Column(name = "terraform_plan")
    private String terraformPlan;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "approval_team")
    private String approvalTeam;

    @Column(name = "tcl")
    private String tcl;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
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

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "pr_number")
    private Integer prNumber;

    @Exclude
    @Column(name = "pr_comment_id")
    private String prCommentId;

    @Exclude
    @Column(name = "command_comment_id")
    private String commandCommentId;

    @Exclude
    @Column(name = "pr_apply_enabled")
    private boolean prApplyEnabled = false;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "pr_comment_error")
    private String prCommentError;

    /**
     * How many dependency hops produced this job: null or 0 when a human or webhook
     * started it, N when it was cascaded from a workspace it depends on. Used to stop a
     * cyclic dependency graph from triggering runs forever.
     */
    @Column(name = "dependency_depth")
    private Integer dependencyDepth;

    @ManyToOne
    private Organization organization;

    @ManyToOne
    private Workspace workspace;

    @UpdatePermission(expression = "user is a super service")
    @OneToMany(mappedBy = "job", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<Step> step;

    @OneToMany(mappedBy = "job", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<Address> address;

    // Requested target/replace resource addresses as submitted on job creation. Consumed by
    // JobManageHook to create the corresponding Address rows (see the `address` relationship
    // above), which is what ExecutorService actually reads to build the terraform CLI flags -
    // these columns are a record of what was requested, not the operational source of truth.
    @Convert(converter = StringListConverter.class)
    @Column(name = "target_addrs")
    private List<String> targetAddrs;

    @Convert(converter = StringListConverter.class)
    @Column(name = "replace_addrs")
    private List<String> replaceAddrs;

}

