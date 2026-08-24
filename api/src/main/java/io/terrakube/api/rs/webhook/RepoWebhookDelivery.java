package io.terrakube.api.rs.webhook;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "repo_webhook_delivery")
public class RepoWebhookDelivery extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    private UUID id;

    // Explicit @JoinColumn: this project's Hibernate physical naming strategy
    // (PhysicalNamingStrategyStandardImpl) does not snake_case implicit names, so a camelCase
    // field like "repoWebhook" would otherwise generate a literal "repoWebhook_id" column instead
    // of matching the changelog's "repo_webhook_id".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repo_webhook_id")
    private RepoWebhook repoWebhook;

    // Plain @Column, not @Lob: see RepoWebhookDeliveryRepository.findDueForDispatch, which runs
    // with no surrounding transaction from a Quartz job - a clob/Large Object column requires one
    // on every read (see notification_outbox for the identical fix and its full rationale).
    private String payload;

    private String headers;

    @Enumerated(EnumType.STRING)
    private RepoWebhookDeliveryStatus status = RepoWebhookDeliveryStatus.PENDING;

    @Column(name = "attempt_count")
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastAttemptAt;

    @Column(name = "next_attempt_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;
}
