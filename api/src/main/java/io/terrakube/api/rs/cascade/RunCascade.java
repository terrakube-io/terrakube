package io.terrakube.api.rs.cascade;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;

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
 * One logical multi-workspace propagation, started by a single upstream job completing.
 * Read-only from the API: written exclusively by the cascade coordinator (a later slice of
 * terrakube-io/terrakube#3629), same write lock as {@code History}. Discoverable to the same
 * audience as the triggers that create it - see {@code WorkspaceRunTrigger}'s own reasoning.
 */
@Include(name = "runCascade")
@ReadPermission(expression = "team view run cascade")
@CreatePermission(expression = "user is a super service")
@UpdatePermission(expression = "user is a super service")
@DeletePermission(expression = "user is a super service")
@Getter
@Setter
@Entity(name = "run_cascade")
public class RunCascade extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    /** The job whose completion started this cascade; its workspace is the root of the graph. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_job_id")
    private Job originJob;

    /** Denormalized from the origin job's workspace, for scoping queries without a join. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    private RunCascadeStatus status = RunCascadeStatus.RUNNING;
}
