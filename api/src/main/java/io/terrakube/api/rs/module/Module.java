package io.terrakube.api.rs.module;

import java.lang.module.ModuleDescriptor.Version;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import com.yahoo.elide.annotation.ComputedAttribute;
import com.yahoo.elide.annotation.ComputedRelationship;
import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import com.yahoo.elide.core.RequestScope;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.module.ModuleManageHook;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@ReadPermission(expression = "team view module")
@CreatePermission(expression = "team manage module")
@UpdatePermission(expression = "team manage module OR user is a super service")
@DeletePermission(expression = "team manage module")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, hook = ModuleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, hook = ModuleManageHook.class)
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

    @OneToMany(cascade = { CascadeType.REMOVE }, mappedBy = "module")
    private List<ModuleVersion> version;

    @Transient
    @ComputedAttribute
    public String getRegistryPath(RequestScope requestScope) {
        return organization.getName() + "/" + name + "/" + provider;
    }

    @Transient
    @ComputedRelationship
    public String getLatestVersion() {
        return version != null && !version.isEmpty()
                ? version.stream()
                        .map(ModuleVersion::getVersion)
                        .max((v1, v2) -> Version.parse(v1.replace("v", ""))
                                .compareTo(Version.parse(v2.replace("v", ""))))
                        .orElse("Version pending")
                : "Version pending";
    }
}
