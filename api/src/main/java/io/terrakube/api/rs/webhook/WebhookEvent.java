package io.terrakube.api.rs.webhook;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;

import com.yahoo.elide.annotation.Include;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Include
@Entity(name = "webhook_event")
public class WebhookEvent extends GenericAuditFields {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    private UUID id;

    private String branch;

    private String path;

    @Column(name = "template_id")
    private String templateId;

    @Column(name = "path_type")
    @Enumerated(EnumType.STRING)
    private WebhookEventPathType pathType = WebhookEventPathType.REGEX;
    
    @Enumerated(EnumType.STRING)
    private WebhookEventType event;
    
    private int priority = 0;

    @Column(name = "pr_workflow_enabled")
    private boolean prWorkflowEnabled = false;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Webhook webhook;
}