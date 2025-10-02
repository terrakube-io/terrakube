package io.terrakube.api.rs.module;

import com.yahoo.elide.annotation.*;
import com.yahoo.elide.core.RequestScope;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.module.ModuleManageHook;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "team view module")
@CreatePermission(expression = "team manage module")
@UpdatePermission(expression = "team manage module OR user is a super service")
@DeletePermission(expression = "team manage module")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, hook = ModuleManageHook.class)
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "module")
@Slf4j
public class Module extends GenericAuditFields {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "provider")
    private String provider;

    @Column(name = "source")
    private String source;

    @Column(name = "tag_prefix")
    private String tagPrefix;

    @Column(name = "folder")
    private String folder;

    @Column(name = "download_quantity")
    private int downloadQuantity = 0;

    @ManyToOne
    private Organization organization;

    @OneToOne
    private Vcs vcs;

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
}
