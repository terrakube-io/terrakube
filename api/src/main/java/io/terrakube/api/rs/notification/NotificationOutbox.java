package io.terrakube.api.rs.notification;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.job.Job;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

@Getter
@Setter
@Entity(name = "notification_outbox")
public class NotificationOutbox extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private NotificationConfiguration configuration;

    // Plain @Column, not @Lob: @Lob maps a String to PostgreSQL's true oid-backed Large Object
    // storage here, which requires every read to happen inside a real (non-autocommit)
    // transaction - the outbox poller's findDueForDispatch() runs with no surrounding
    // transaction (a plain repository query call from a Quartz job), so every poll tick threw
    // "Large Objects may not be used in auto-commit mode." A plain text column has no such
    // requirement and, unlike a Java in-memory Lob illusion, is PostgreSQL's native
    // unbounded-length string type anyway - see changelog-2.33.0-notification.xml.
    private String payload;

    @Enumerated(EnumType.STRING)
    private NotificationOutboxStatus status = NotificationOutboxStatus.PENDING;

    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastAttemptAt;

    // Null means "due as soon as picked up" (first attempt, or a retryable failure whose
    // backoff is purely a function of attemptCount). Set explicitly when a failure carries
    // a server-supplied delay (e.g. Slack's Retry-After on 429) that must not be undercut
    // by the fixed attemptCount-based backoff.
    @Column(name = "next_attempt_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;
}
