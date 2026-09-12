package io.terrakube.api.rs.policy;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.UUID;

@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "policy_set_parameter")
@ReadPermission(expression = "team view policy set parameter")
@CreatePermission(expression = "team manage policy set parameter")
@UpdatePermission(expression = "team manage policy set parameter")
@DeletePermission(expression = "team manage policy set parameter")
public class PolicySetParameter extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parameter_key", nullable = false)
    private String key;

    @Column(name = "parameter_value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "description", length = 1024)
    private String description;

    @ManyToOne(optional = false)
    @JoinColumn(name = "policy_set_id")
    private PolicySet policySet;
}
