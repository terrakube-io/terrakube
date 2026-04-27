package io.terrakube.api.rs.webhook;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.vcs.Vcs;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "repo_webhook")
public class RepoWebhook extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_url", length = 1024, unique = true)
    private String repositoryUrl;

    @Column(name = "remote_hook_id")
    private String remoteHookId;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    private Vcs vcs;
}
