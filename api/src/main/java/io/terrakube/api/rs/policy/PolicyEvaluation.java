package io.terrakube.api.rs.policy;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
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
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.UUID;

@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "policy_evaluation")
@ReadPermission(expression = "team view policy evaluation")
@CreatePermission(expression = "user is a super service")
@UpdatePermission(expression = "user is a super service")
@DeletePermission(expression = "user is a super service")
public class PolicyEvaluation extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "job_id")
    private Job job;

    @ManyToOne(optional = false)
    @JoinColumn(name = "step_id")
    private Step step;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private PolicyEvaluationStatus status;

    @Column(name = "passed_rules")
    private int passedRules;

    @Column(name = "warning_rules")
    private int warningRules;

    @Column(name = "soft_mandatory_violations")
    private int softMandatoryViolations;

    @Column(name = "hard_mandatory_violations")
    private int hardMandatoryViolations;

    @Column(name = "shadow_hard_violations")
    private int shadowHardViolations;

    @Column(name = "shadow_soft_violations")
    private int shadowSoftViolations;

    @Column(name = "storage_uri", length = 1024)
    private String storageUri;

    @OneToOne(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true)
    private PolicyOverride override;
}
