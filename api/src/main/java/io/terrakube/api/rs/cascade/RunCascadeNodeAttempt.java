package io.terrakube.api.rs.cascade;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.job.Job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * One dispatched attempt at running a {@link RunCascadeNode}. The node's own status is terminal
 * once an attempt succeeds or attempts are exhausted; outcome is read from {@link #job}'s status
 * rather than duplicated here.
 */
@Getter
@Setter
@Entity(name = "run_cascade_node_attempt")
public class RunCascadeNodeAttempt extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RunCascadeNode node;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Job job;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;
}
