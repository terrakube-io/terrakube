package io.terrakube.api.rs.cascade;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

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
 * One logical multi-workspace propagation, started by a single upstream job completing. Not an
 * Elide resource yet - like {@code RunTriggerEvent}, this is written and read by the coordinator;
 * read/action endpoints are a later slice (terrakube-io/terrakube#3629).
 */
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
