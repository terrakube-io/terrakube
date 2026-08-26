package io.terrakube.api.rs.project.access;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.project.Project;

import java.sql.Types;
import java.util.UUID;

@ReadPermission(expression = "team manage project access OR team project limited manage project access")
@CreatePermission(expression = "user is a superuser OR team manage project access OR team project limited manage project access")
@UpdatePermission(expression = "user is a superuser OR team manage project access OR team project limited manage project access")
@DeletePermission(expression = "user is a superuser OR team manage project access OR team project limited manage project access")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "project_access")
public class ProjectAccess {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "manage_state")
    private boolean manageState;

    @Column(name = "manage_job")
    private boolean manageJob;

    @Column(name = "manage_workspace")
    private boolean manageWorkspace;

    @Column(name = "plan_job")
    private boolean planJob;

    @Column(name = "approve_job")
    private boolean approveJob;

    @Column(name = "role")
    private String role;

    @ManyToOne
    private Project project;
}
