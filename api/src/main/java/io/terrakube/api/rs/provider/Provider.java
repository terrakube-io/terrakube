package io.terrakube.api.rs.provider;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.rs.provider.SourceType;
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

    @Column(name = "imported")
    private boolean imported = false;

    @Column(name = "registry_namespace")
    private String registryNamespace;

    /**
     * Where versions of this provider are imported from.
     * TERRAFORM_REGISTRY (default): a Terraform provider registry resolved via service discovery.
     * REPOSITORY: a base URL hosting goreleaser style release assets (repo release page or web server).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type")
    private SourceType sourceType = SourceType.TERRAFORM_REGISTRY;

    /**
     * Host of the Terraform provider registry to import from (e.g. registry.terraform.io,
     * gitlab.example.com, artifactory.example.com). Null/empty falls back to the public registry.
     */
    @Column(name = "registry_host")
    private String registryHost;

    /** Base URL holding the release assets when sourceType is REPOSITORY. */
    @Column(name = "repository_url")
    private String repositoryUrl;

    /** Comma separated list of versions to import when sourceType is REPOSITORY. */
    @Column(name = "repository_versions")
    private String repositoryVersions;

    /** GPG key id used to verify SHA256SUMS for a REPOSITORY source. */
    @Column(name = "gpg_key_id")
    private String gpgKeyId;

    /** ASCII armored GPG public key used to verify SHA256SUMS for a REPOSITORY source. */
    @Column(name = "gpg_ascii_armor")
    private String gpgAsciiArmor;

    /** Optional bearer token used to authenticate against a private registry/repository. */
    @ReadPermission(expression = "team manage provider")
    @Column(name = "registry_token")
    private String registryToken;

    @ManyToOne
    private Organization organization;

    @OneToMany(mappedBy = "provider")
    private List<Version> version;
}
