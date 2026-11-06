package io.terrakube.api.rs.announcement;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.announcement.AnnouncementManageHook;
import jakarta.persistence.*;
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
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = AnnouncementManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT, hook = AnnouncementManageHook.class)
@Include
@Getter
@Setter
@Entity(name = "announcement")
@Table(name = "announcement")
public class Announcement extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "message", columnDefinition = "text", nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private AnnouncementSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private AnnouncementScope scope;

    @ManyToOne
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "starts_at")
    private Date startsAt;

    @Column(name = "ends_at")
    private Date endsAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_dismissible", nullable = false)
    private boolean dismissible = true;

    @Column(name = "display_on_login", nullable = false)
    private boolean displayOnLogin = false;
}

