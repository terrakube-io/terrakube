package io.terrakube.api.rs.provider;

import com.yahoo.elide.annotation.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.provider.ProviderManageHook;
import io.terrakube.api.rs.provider.implementation.Version;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "team view provider")
@CreatePermission(expression = "team manage provider")
@UpdatePermission(expression = "team manage provider")
@DeletePermission(expression = "team manage provider")
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ProviderManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, hook = ProviderManageHook.class)
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "provider")
public class Provider {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @ManyToOne
    private Organization organization;

    @OneToMany(mappedBy = "provider")
    private List<Version> version;
}
