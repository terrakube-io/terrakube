package io.terrakube.api.rs.webhook;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.hooks.webhook.WebhookEventManageHook;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;

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
@ReadPermission(expression = "team view webhook event")
@CreatePermission(expression = "team manage webhook event")
@UpdatePermission(expression = "team manage webhook event")
@DeletePermission(expression = "team delete webhook event")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = WebhookEventManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = WebhookEventManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = WebhookEventManageHook.class)
@Entity(name = "webhook_event")
// Hidden with its webhook when the workspace is soft deleted, see Webhook.
@SQLRestriction("webhook_id in (select wh.id from webhook wh join workspace w on w.id = wh.workspace_id where w.deleted = false)")
public class WebhookEvent extends GenericAuditFields {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
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

    @Column(name = "pr_apply_enabled")
    private boolean prApplyEnabled = false;

    /** Who may apply from a "terrakube apply" comment; the organization minimum can raise it. */
    @Column(name = "pr_apply_requirement")
    @Enumerated(EnumType.STRING)
    private PrApplyRequirement prApplyRequirement = PrApplyRequirement.WRITE;

    /** A null from the API means the default; the column is NOT NULL. */
    public void setPrApplyRequirement(PrApplyRequirement prApplyRequirement) {
        this.prApplyRequirement = prApplyRequirement == null ? PrApplyRequirement.WRITE : prApplyRequirement;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Webhook webhook;
}
