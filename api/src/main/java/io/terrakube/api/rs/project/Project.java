package io.terrakube.api.rs.project;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.access.ProjectAccess;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "team view project OR team project access view project")
@CreatePermission(expression = "team manage project")
@UpdatePermission(expression = "team manage project OR team project admin manages project access field")
@DeletePermission(expression = "team manage project")
@Include
@Getter
@Setter
@Entity(name = "project")
public class Project extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @ManyToOne
    private Organization organization;

    @UpdatePermission(expression = "team manage project OR team project admin manages project access field")
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProjectAccess> projectAccess = new ArrayList<>();
}
