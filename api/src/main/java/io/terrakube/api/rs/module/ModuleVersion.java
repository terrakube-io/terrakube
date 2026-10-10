package io.terrakube.api.rs.module;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.UpdatePermission;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.terrakube.api.rs.DeprecationMessageConverter;
import io.terrakube.api.rs.VersionStatus;
import io.terrakube.api.rs.hooks.module.ModuleVersionManageHook;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "module_version")
@Include(rootLevel = false)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleVersionManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleVersionManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ModuleVersionManageHook.class)
@CreatePermission(expression = "team manage module version")
@UpdatePermission(expression = "team manage module version OR user is a super service OR user is a registry service")
@DeletePermission(expression = "team manage module version")
public class ModuleVersion {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @ManyToOne
    private Module module;
    
    @Column(name = "version")
    private String version;
    
    @Column(name = "commit_info")
    private String commit;

    @Column(name = "git_tag")
    private String gitTag;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VersionStatus status = VersionStatus.active;

    // Shown for deprecated and removed versions, e.g. a removal date or upgrade instructions.
    @Size(max = 1024)
    @Convert(converter = DeprecationMessageConverter.class)
    @Column(name = "deprecation_message")
    private String deprecationMessage;
}
