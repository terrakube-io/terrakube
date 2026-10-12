package io.terrakube.api.rs.cascade;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.job.Job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * One job attempt within a workspace run. Its completion outcome remains independent of later
 * attempts; an operator retry supersedes the particular failed attempt it replaces. Job ownership
 * and attempt numbering are unique in the database. Ordinary API readers cannot mutate attempts.
 */
@Include(name = "runCascadeNodeAttempt", rootLevel = false)
@ReadPermission(expression = "team view run cascade node attempt")
@CreatePermission(expression = "user is a super service")
@UpdatePermission(expression = "user is a super service")
@DeletePermission(expression = "user is a super service")
@Getter
@Setter
@Entity(name = "run_cascade_node_attempt")
public class RunCascadeNodeAttempt extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @com.yahoo.elide.annotation.Exclude
    private RunCascadeNode node;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Job job;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private RunCascadeNodeStatus outcome = RunCascadeNodeStatus.RUNNING;

    private boolean superseded;

    @Column(name = "state_changed")
    private Boolean stateChanged;
}
