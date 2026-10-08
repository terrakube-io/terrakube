package io.terrakube.api.rs.webhook;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.vcs.Vcs;

import com.yahoo.elide.annotation.Exclude;

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

// Not an Elide resource: this table is an internal dispatch/retry ledger (debugging aid), not
// API-consumer data, and it carries raw request payloads/headers from VCS providers that
// shouldn't be exposed - even to admins - through the JSON-API/GraphQL endpoints.
@Exclude
@Getter
@Setter
@Entity(name = "repo_webhook_delivery")
public class RepoWebhookDelivery extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    // Explicit @JoinColumn: this project's Hibernate physical naming strategy
    // (PhysicalNamingStrategyStandardImpl) does not snake_case implicit names, so a camelCase
    // field like "repoWebhook" would otherwise generate a literal "repoWebhook_id" column instead
    // of matching the changelog's "repo_webhook_id".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repo_webhook_id")
    private RepoWebhook repoWebhook;

    // Set only for deliveries received through a GitHub App webhook: the VCS whose secret
    // verified the signature, which scopes the fan-out (see RepoWebhookService.processClaimedDelivery).
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "vcs_id")
    private Vcs vcs;

    // The verified X-Hub-Signature-256 of a GitHub App delivery, null for every other delivery. It
    // binds to the body, so the unique index drops GitHub redeliveries and replays under a new
    // X-GitHub-Delivery alike. Non-null marks an App delivery (see RepoWebhookDeliveryTransactions.claim).
    @Column(name = "dedupe_key")
    private String dedupeKey;

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
