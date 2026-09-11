package io.terrakube.api.rs.policy;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.vcs.Vcs;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Include
@Getter
@Setter
@Entity(name = "policy_set")
@ReadPermission(expression = "team view policy set")
@CreatePermission(expression = "team manage policy set")
@UpdatePermission(expression = "team manage policy set")
@DeletePermission(expression = "team manage policy set")
public class PolicySet extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "enforcement_level", nullable = false)
    @Enumerated(EnumType.STRING)
    private PolicyEnforcementLevel enforcementLevel = PolicyEnforcementLevel.HARD_MANDATORY;

    @Column(name = "shadow_enforcement_level")
    @Enumerated(EnumType.STRING)
    private PolicyEnforcementLevel shadowEnforcementLevel;

    @Column(name = "override_team")
    private String overrideTeam;

    @Column(name = "global", nullable = false)
    private boolean global = false;

    @Column(name = "repository")
    private String repository;

    @Column(name = "branch")
    private String branch = "main";

    @Column(name = "folder")
    private String folder = "/";

    @Column(name = "opa_version")
    private String opaVersion;

    @ManyToOne
    @JoinColumn(name = "vcs_id")
    private Vcs vcs;

    @ManyToOne(optional = false)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne
    @JoinColumn(name = "notification_configuration_id")
    private NotificationConfiguration notificationConfiguration;

    @OneToMany(mappedBy = "policySet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PolicyAttachment> attachments;

    @OneToMany(mappedBy = "policySet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PolicyExemption> exemptions;
}
