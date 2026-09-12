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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "policy_override")
@ReadPermission(expression = "team view policy override")
@CreatePermission(expression = "team override policy")
@UpdatePermission(expression = "user is a super service")
@DeletePermission(expression = "user is a super service")
public class PolicyOverride extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(optional = false)
    @JoinColumn(name = "evaluation_id")
    private PolicyEvaluation evaluation;

    @Column(name = "overridden_by", nullable = false)
    private String overriddenBy;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "overridden_at", nullable = false)
    private Date overriddenAt;

    @Column(name = "justification", nullable = false, length = 2048)
    private String justification;
}
