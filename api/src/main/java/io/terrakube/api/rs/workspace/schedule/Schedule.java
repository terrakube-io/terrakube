package io.terrakube.api.rs.workspace.schedule;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.hooks.schedule.ScheduleManageHook;
import io.terrakube.api.rs.workspace.Workspace;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.CREATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ScheduleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.UPDATE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ScheduleManageHook.class)
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, phase = LifeCycleHookBinding.TransactionPhase.POSTCOMMIT, hook = ScheduleManageHook.class)
@Include(rootLevel = false)
@ReadPermission(expression = "team view schedule")
@CreatePermission(expression = "team manage schedule")
@UpdatePermission(expression = "team manage schedule")
@DeletePermission(expression = "team manage schedule")
@Getter
@Setter
@Entity(name = "schedule")
public class Schedule extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cron")
    private String cron;

    @Deprecated(since = "1.9")
    @Column(name = "tcl")
    private String tcl;

    @Column(name = "template_reference")
    private String templateReference;

    @Column(name = "description")
    private String description;

    @Column(name = "enabled")
    private boolean enabled = true;

    @ManyToOne
    private Workspace workspace;
}
