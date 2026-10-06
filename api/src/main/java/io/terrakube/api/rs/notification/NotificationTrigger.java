package io.terrakube.api.rs.notification;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.JobStatus;

import com.yahoo.elide.annotation.Include;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Include
@Entity(name = "notification_trigger")
public class NotificationTrigger extends GenericAuditFields {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_status")
    @Enumerated(EnumType.STRING)
    private JobStatus jobStatus;

    /** Mutually exclusive with {@link #jobStatus} - a row describes one job-run status or one
     *  cascade outcome (COMPLETED/DEGRADED/BLOCKED/CANCELLED), never both. */
    @Column(name = "cascade_status")
    @Enumerated(EnumType.STRING)
    private RunCascadeStatus cascadeStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private NotificationConfiguration configuration;
}
