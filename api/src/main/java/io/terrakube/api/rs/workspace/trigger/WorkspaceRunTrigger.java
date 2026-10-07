package io.terrakube.api.rs.workspace.trigger;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.Paginate;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.hooks.trigger.WorkspaceRunTriggerHook;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.UUID;

/**
 * A run trigger: the destination workspace subscribes to state-changing runs of the source
 * workspace, so that a successful apply upstream enqueues a run downstream.
 *
 * The edge is directed and both ends must belong to the same organization. Reads are open to
 * any member of the organization so the dependency graph is discoverable, while creating or
 * changing an edge requires manage rights on the destination - see
 * {@code TeamManageWorkspaceTrigger}.
 *
 * <p>{@code synchronizationMode} and {@code onDestroy} are declared here per edge and read by
 * {@code RunTriggerDispatchService}/{@code RunCascadeCoordinatorService} at dispatch time, not
 * by this class itself.
 *
 * <p>Paginated well above Elide's 500-row framework default (same fix as {@code Tag}/
 * {@code WorkspaceTag}): the organization dependency graph fetches every edge in one request,
 * and silently truncating that for a large organization would drop real edges from the graph
 * rather than erroring, which is worse than just returning everything up to a generous cap.
 */
@Paginate(defaultPageSize = 10000, maxPageSize = 10000)
@ReadPermission(expression = "team view workspace trigger")
@CreatePermission(expression = "team manage workspace trigger")
@UpdatePermission(expression = "team manage workspace trigger")
@DeletePermission(expression = "team delete workspace trigger")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = WorkspaceRunTriggerHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = WorkspaceRunTriggerHook.class)
@Include(name = "runTrigger")
@Getter
@Setter
@Entity(name = "workspace_run_trigger")
@Table(name = "workspace_run_trigger", uniqueConstraints = {
        @UniqueConstraint(name = "uk_run_trigger_source_dest",
                columnNames = {"source_workspace_id", "destination_workspace_id"})
})
public class WorkspaceRunTrigger extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The upstream workspace whose successful apply fires this trigger.
     *
     * Immutable once set: repointing an edge is deleting one dependency and declaring
     * another, and both deserve to go through their own permission checks. PATCH remains
     * open for enabled and template.
     */
    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "source_workspace_id", nullable = false)
    private Workspace sourceWorkspace;

    /** The downstream workspace that gets a run when the source applies. Immutable, as above. */
    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_workspace_id", nullable = false)
    private Workspace destinationWorkspace;

    /**
     * Template used for the triggered run. When null the destination workspace's
     * defaultTemplate is used, which keeps the common case free of configuration.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private Template template;

    /**
     * Denormalized owner of the edge, derived from the destination workspace by
     * WorkspaceRunTriggerHook. Excluded from the API surface entirely rather than merely
     * permission-guarded: a client has no reason to set it, and accepting it would let an
     * edge be attributed to a tenant that owns neither workspace, exposing it to the wrong
     * organization through the read check. Keeping it on the row lets the
     * cycle validation load an organization's whole graph in a single indexed query instead
     * of walking through the workspaces; letting a client set it would let the edge be
     * attributed to a tenant that owns neither workspace.
     */
    @Exclude
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Lets an edge be turned off without losing its configuration. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** How the destination waits when it has more than one enabled upstream edge. Per-edge, not global. */
    @Enumerated(EnumType.STRING)
    @Column(name = "synchronization_mode", nullable = false)
    private RunTriggerSynchronizationMode synchronizationMode = RunTriggerSynchronizationMode.EACH;

    /** What a destroy on the source does to this edge's destination. */
    @Enumerated(EnumType.STRING)
    @Column(name = "on_destroy", nullable = false)
    private RunTriggerOnDestroyPolicy onDestroy = RunTriggerOnDestroyPolicy.TRIGGER;

    /**
     * Required when {@code onDestroy} is {@code PLAN_ONLY}: the template dispatched on the
     * destination instead of its normal template, so the drift is only ever planned, never
     * applied. There is no safe default to fall back to here - the destination's own default
     * template could just as easily be an apply template, which would defeat the policy.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "on_destroy_plan_template_id")
    private Template onDestroyPlanTemplate;

    /**
     * Derives the owning organization from the destination workspace.
     *
     * This runs as a JPA callback rather than an Elide lifecycle hook on purpose: Elide's
     * PRECOMMIT phase fires after Hibernate has already flushed the insert, which leaves a
     * NOT NULL column unset and fails at the database. @PrePersist is guaranteed to run
     * before the statement is built.
     */
    @PrePersist
    @PreUpdate
    private void deriveOrganization() {
        if (destinationWorkspace != null) {
            this.organization = destinationWorkspace.getOrganization();
        }
    }
}
