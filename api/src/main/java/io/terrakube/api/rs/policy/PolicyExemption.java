package io.terrakube.api.rs.policy;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

@Include
@Getter
@Setter
@Entity(name = "policy_exemption")
@ReadPermission(expression = "team view policy exemption")
@CreatePermission(expression = "team manage policy exemption")
@UpdatePermission(expression = "team manage policy exemption")
@DeletePermission(expression = "team manage policy exemption")
public class PolicyExemption extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "policy_set_id")
    private PolicySet policySet;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @ManyToOne
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "ticket_reference", nullable = false)
    private String ticketReference;

    @Column(name = "justification", nullable = false, length = 2048)
    private String justification;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "expires_at")
    private Date expiresAt;

    @ManyToOne(optional = false)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
