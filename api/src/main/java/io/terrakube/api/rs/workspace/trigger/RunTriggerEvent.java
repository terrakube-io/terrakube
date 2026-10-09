package io.terrakube.api.rs.workspace.trigger;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.job.Job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.Getter;
import lombok.Setter;

/**
 * Durable record that a completed job needs its run triggers evaluated, written in the same
 * transaction that marks the job completed so a crash between the two can't lose it. Not an
 * Elide resource - like {@code NotificationOutbox}, this is internal queue plumbing. No payload
 * is duplicated here; everything dispatch needs is reachable from {@link #job}.
 */
@Getter
@Setter
@Entity(name = "run_trigger_event")
public class RunTriggerEvent extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    /** One event per completed job - the natural idempotency key, enforced by a unique constraint. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Job job;

    @Enumerated(EnumType.STRING)
    private RunTriggerEventStatus status = RunTriggerEventStatus.PENDING;

    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastAttemptAt;

    // Null means "due as soon as picked up" (first attempt). Set to now + backoff(attemptCount)
    // on a retryable failure.
    @Column(name = "next_attempt_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date nextAttemptAt;

    // Plain @Column, not @Lob - see NotificationOutbox.payload for why.
    @Column(name = "last_error")
    private String lastError;
}
