package io.terrakube.api.rs.job.step;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.LogStatus;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@ReadPermission(expression = "team view job step OR team project limited view job step OR team limited view job step OR executor read job step OR user is an executor service")
@CreatePermission(expression = "user is a super service")
@UpdatePermission(expression = "user is a super service OR executor manage job step OR user is an executor service")
@DeletePermission(expression = "user is a super service")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "step")
public class Step {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "step_number")
    private int stepNumber;

    @Column(name = "name")
    private String name;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    @Exclude
    @Column(name = "log_status")
    @Enumerated(EnumType.STRING)
    private LogStatus logStatus;

    @Column(name = "output")
    private String output;

    @Column(name = "delivery_state")
    @Enumerated(EnumType.STRING)
    private DeliveryState deliveryState;

    @Column(name = "delivery_revision")
    private Long deliveryRevision;

    @Column(name = "delivery_timestamp")
    private Long deliveryTimestamp;

    @Exclude
    @Column(name = "lease_owner")
    private String leaseOwner;

    @Exclude
    @Column(name = "lease_until")
    private java.util.Date leaseUntil;

    @ManyToOne
    private Job job;

    @OneToMany(mappedBy = "step", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private java.util.List<io.terrakube.api.rs.policy.PolicyEvaluation> policyEvaluation;
}
