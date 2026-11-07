package io.terrakube.api.rs.module;

import com.yahoo.elide.annotation.*;
import com.yahoo.elide.core.RequestScope;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.module.ModuleManageHook;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "team view module OR user is a registry service")
@CreatePermission(expression = "team manage module")
@UpdatePermission(expression = "team manage module OR user is a super service OR user is a registry service")
@DeletePermission(expression = "team manage module")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, hook = ModuleManageHook.class)
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "module")
// Only changed columns are written, so a PATCH (including the registry download count) never writes
// back refresh columns that the poller changed after the entity was loaded.
@DynamicUpdate
@Slf4j
public class Module extends GenericAuditFields {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "provider")
    private String provider;

    // A change to source, tagPrefix, vcs or ssh can change which tags the module has, so ModuleManageHook
    // makes the module due for a refresh. A change to source or vcs also syncs the repository webhook when module
    // webhooks are enabled.
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PREFLUSH, hook = ModuleManageHook.class)
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleManageHook.class)
    @Column(name = "source")
    private String source;

    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PREFLUSH, hook = ModuleManageHook.class)
    @Column(name = "tag_prefix")
    private String tagPrefix;

    @Column(name = "folder")
    private String folder;

    @Column(name = "download_quantity")
    private int downloadQuantity = 0;

    @ManyToOne
    private Organization organization;

    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PREFLUSH, hook = ModuleManageHook.class)
    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleManageHook.class)
    @OneToOne
    private Vcs vcs;

    @LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PREFLUSH, hook = ModuleManageHook.class)
    @OneToOne
    private Ssh ssh;

    @OneToMany(cascade = {CascadeType.REMOVE}, mappedBy = "module")
    private List<ModuleVersion> version;

    @Transient
    @ComputedAttribute
    public String getRegistryPath(RequestScope requestScope) {
        return organization.getName() + "/" + name + "/" + provider;
    }

    @Column(name = "latest_version")
    private String latestVersion;

    // Refresh state, written by the module refresh poller only. NULL next_refresh means due now; while a
    // refresh runs it holds the lease expiry.
    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "next_refresh")
    @Temporal(TemporalType.TIMESTAMP)
    private Date nextRefresh;

    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "last_refresh")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastRefresh;

    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "last_tag_detected_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastTagDetectedAt;

    // Why the last refresh failed, null when it worked.
    @CreatePermission(expression = "NONE")
    @UpdatePermission(expression = "NONE")
    @Column(name = "last_refresh_error", length = 1024)
    private String lastRefreshError;
}
