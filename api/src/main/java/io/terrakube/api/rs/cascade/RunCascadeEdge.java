package io.terrakube.api.rs.cascade;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerOnDestroyPolicy;
import io.terrakube.api.rs.workspace.trigger.RunTriggerSynchronizationMode;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * One edge of a cascade's reachable subgraph, as it stood when the cascade was created. A
 * snapshot, not a live reference: the real {@link WorkspaceRunTrigger} it was copied from can
 * change or be deleted after this row is written, and a running cascade must keep evaluating
 * against the shape it started with.
 */
@Getter
@Setter
@Entity(name = "run_cascade_edge")
public class RunCascadeEdge extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RunCascade cascade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_workspace_id")
    private Workspace sourceWorkspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_workspace_id")
    private Workspace destinationWorkspace;

    /** Copied from the live edge at snapshot time, not read from it afterwards. */
    @Enumerated(EnumType.STRING)
    @Column(name = "synchronization_mode")
    private RunTriggerSynchronizationMode synchronizationMode;

    /** Copied from the live edge at snapshot time, not read from it afterwards. */
    @Enumerated(EnumType.STRING)
    @Column(name = "on_destroy")
    private RunTriggerOnDestroyPolicy onDestroy;

    /** Traceability only - the live row this snapshot came from may since have changed or been deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_trigger_id")
    private WorkspaceRunTrigger sourceTrigger;
}
