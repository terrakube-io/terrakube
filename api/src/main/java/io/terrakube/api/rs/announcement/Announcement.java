package io.terrakube.api.rs.announcement;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.announcement.AnnouncementManageHook;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

@ReadPermission(expression = "announcement read check")
@CreatePermission(expression = "announcement manage check")
@UpdatePermission(expression = "announcement manage check")
@DeletePermission(expression = "announcement manage check")
@LifeCycleHookBinding(
        operation = LifeCycleHookBinding.Operation.CREATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
        hook = AnnouncementManageHook.class)
@LifeCycleHookBinding(
        operation = LifeCycleHookBinding.Operation.UPDATE,
        phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
        hook = AnnouncementManageHook.class)
@Include
@Getter
@Setter
@Entity(name = "announcement")
public class Announcement extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank(message = "title is required")
    @Size(max = 120, message = "title must be at most 120 characters")
    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @NotBlank(message = "message is required")
    @Column(name = "message", columnDefinition = "text", nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private AnnouncementSeverity severity = AnnouncementSeverity.INFO;

    @UpdatePermission(expression = "user is a superuser")
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private AnnouncementScope scope = AnnouncementScope.ORGANIZATION;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false)
    private AnnouncementScheduleType scheduleType = AnnouncementScheduleType.IMMEDIATE;

    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "starts_at")
    private Date startsAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "ends_at")
    private Date endsAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_dismissible", nullable = false)
    private boolean dismissible = true;

    @Column(name = "display_on_login", nullable = false)
    private boolean displayOnLogin = false;
}
