package io.terrakube.api.rs.cascade;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.workspace.Workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * One workspace's position within a cascade's reachable subgraph. One row per (cascade,
 * workspace) pair - a diamond's join workspace gets a single node, not one per parent, which is
 * what lets {@code ANY}/{@code ALL} evaluate it once instead of once per incoming edge.
 */
@Getter
@Setter
@Entity(name = "run_cascade_node")
public class RunCascadeNode extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RunCascade cascade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Workspace workspace;

    /** Hop count from the origin workspace; the origin itself is depth 0. */
    @Column(nullable = false)
    private int depth;

    @Enumerated(EnumType.STRING)
    private RunCascadeNodeStatus status = RunCascadeNodeStatus.PENDING;
}
