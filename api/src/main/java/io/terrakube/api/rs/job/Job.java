package io.terrakube.api.rs.job;

import java.util.List;
import java.util.Map;
import java.util.Date;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.job.JobManageHook;
import io.terrakube.api.rs.hooks.job.JobVariablesHook;
import io.terrakube.api.rs.hooks.job.JobApprovalHook;
import io.terrakube.api.rs.hooks.job.JobArtifactsCleanupHook;
import io.terrakube.api.rs.hooks.notification.JobNotificationHook;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

import com.yahoo.elide.annotation.ComputedAttribute;
import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.PREFLUSH, hook = JobVariablesHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = JobManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = JobManageHook.class)
@ReadPermission(expression = "team view job OR team project limited view job OR team limited view job OR executor read job OR user is an executor service")
// Creating a job needs plan rights. "manage job" also matches approve-only access, which must not queue new runs.
@CreatePermission(expression = "team plan job OR team limited plan job OR team project limited plan job")
@UpdatePermission(expression = "team manage job OR team limited manage job OR team project limited manage job OR user is a super service OR executor manage job OR user is an executor service")
@DeletePermission(expression = "user is a super service OR user is a superuser")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "job")
@SQLRestriction(value = "deleted = false")
public class Job extends GenericAuditFields {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    // Create-only input. JobVariablesHook validates and encrypts it into encryptedVariables before the
    // flush, and it is never persisted, read or updated through the API.
    @ReadPermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Transient
    @ComputedAttribute
    private Map<String, String> variables;

    // Encrypted JSON of the job variables. Only decrypted when the job is dispatched to the executor.
    @ReadPermission(expression = "NONE")
    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "encrypted_variables", columnDefinition = "text")
    private String encryptedVariables;

    // Names (never values) of the job variables, so the run details and audit trail can show that a run
    // used them. Set by JobVariablesHook, read-only through the API.
    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Convert(converter = StringListConverter.class)
    @Column(name = "variable_names", columnDefinition = "text")
    private List<String> variableNames;

    // True for the variables a Terraform CLI run supplied with -var, -var-file or TF_VAR_: the executor then passes
    // them as --var arguments, which rank above the configuration's tfvars files like they do on a local run.
    // False (the default) keeps the TF_VAR_ environment variables of jobs created through the job API.
    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "variables_as_arguments", nullable = false)
    private boolean variablesAsArguments = false;

    @Column(name = "comments")
    private String comments;

    @UpdatePermission(expression = "team approve job OR team approve job rbac OR team limited approve job OR team project limited approve job OR user is a super service OR executor manage job OR user is an executor service")
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = JobApprovalHook.class)
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = JobApprovalHook.class)
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = JobNotificationHook.class)
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = JobNotificationHook.class)
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = JobArtifactsCleanupHook.class)
    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.pending;

    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "approved_by")
    private String approvedBy;

    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "approved_at")
    private Date approvedAt;

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

    // Where the executor stored the files a plan step produced for its apply step, and their
    // sha256. Written by the executor only: the apply step reads the location from here, so a user
    // who could set it could point an apply at another job's bundle.
    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service OR executor manage job OR user is an executor service")
    @Column(name = "terraform_plan_artifacts")
    private String terraformPlanArtifacts;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service OR executor manage job OR user is an executor service")
    @Column(name = "terraform_plan_artifacts_checksum")
    private String terraformPlanArtifactsChecksum;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service OR executor manage job OR user is an executor service")
    @Column(name = "approval_team")
    private String approvalTeam;

    @Exclude
    @Column(name = "tcl")
    private String tcl;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "override_source")
    private String overrideSource;

    @Column(name = "override_branch")
    private String overrideBranch;

    @UpdatePermission(expression = "NONE")
    @Column(name = "template_reference")
    private String templateReference;

    @Column(name = "via")
    private String via = JobVia.UI.getValue();

    @Column(name = "refresh")
    private boolean refresh = true;

    @Column(name = "plan_changes")
    private boolean planChanges = true;

    @Column(name = "refresh_only")
    private boolean refreshOnly = false;

    /**
     * The concrete version the workspace's version constraint resolved to for this job. The executor pins
     * it the first time it resolves one (through JobResolvedVersionController, which writes it atomically
     * and only once), and every later step is dispatched with it: a plan file can only be read by the exact
     * version that wrote it, so a constraint such as ">= 1.3.0" must not be re-resolved between plan and
     * apply. Read-only through the generic API.
     */
    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "resolved_terraform_version")
    private String resolvedTerraformVersion;

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
     * The job whose successful apply caused this one, when it was created by a run trigger.
     * Null for every job started by a person, a webhook or a schedule.
     */
    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "triggered_by_job_id")
    private Integer triggeredByJobId;

    /**
     * How many trigger hops produced this job: 0 when it was started directly, N when it is
     * the Nth link of a chain. Bounds propagation if the graph ever contains a cycle that
     * static validation did not catch.
     */
    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "cascade_depth", nullable = false)
    private int cascadeDepth = 0;

    // Bounded dispatch-retry bookkeeping for pre-submission dispatch failures (e.g. VCS
    // access-token acquisition) - see ScheduleJob.dispatchStepOrDefer / DispatchRetryCalculator.
    // Persisted (rather than kept in memory) so the backoff/budget survives API restarts and is
    // consistent across replicas; read-only via the API like the other scheduler-owned fields
    // above, exposing retry status/failure reason in run details.
    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "dispatch_failure_count", nullable = false)
    private int dispatchFailureCount = 0;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "dispatch_first_failure_at")
    private Date dispatchFirstFailureAt;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "dispatch_last_attempt_at")
    private Date dispatchLastAttemptAt;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "dispatch_next_retry_at")
    private Date dispatchNextRetryAt;

    @CreatePermission(expression = "user is a super service")
    @UpdatePermission(expression = "user is a super service")
    @Column(name = "dispatch_last_error", length = 1024)
    private String dispatchLastError;

    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne
    @JoinColumn(name = "organization_id", updatable = false)
    private Organization organization;

    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne
    @JoinColumn(name = "workspace_id", updatable = false)
    private Workspace workspace;

    @UpdatePermission(expression = "user is a super service OR executor manage job OR user is an executor service")
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

    @OneToMany(mappedBy = "job", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<io.terrakube.api.rs.policy.PolicyEvaluation> policyEvaluation;
}
