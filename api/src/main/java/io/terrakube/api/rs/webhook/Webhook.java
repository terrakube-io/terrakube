package io.terrakube.api.rs.webhook;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.hooks.webhook.WebhookManageHook;
import io.terrakube.api.rs.workspace.Workspace;

import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;

@Include
@Getter
@Setter
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = WebhookManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = WebhookManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = WebhookManageHook.class)
@Entity(name = "webhook")
public class Webhook extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    private UUID id;

    @Column(name = "remote_hook_id")
    private String remoteHookId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private Workspace workspace;
    
    @Column(name = "migrated_v2")
    private boolean migratedV2 = false;

    @OneToMany(mappedBy = "webhook", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private List<WebhookEvent> events;
}