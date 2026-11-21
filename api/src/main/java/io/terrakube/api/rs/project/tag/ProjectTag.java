package io.terrakube.api.rs.project.tag;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.Paginate;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * A tag every workspace in the project should carry. It is copied onto workspaces created in or moved into the
 * project, see {@code WorkspaceProjectTagHook}; removing it does not remove the tag from workspaces that have it.
 */
@ReadPermission(expression = "user is a superuser OR project tag visible")
@CreatePermission(expression = "user is a superuser OR project tag manage")
@UpdatePermission(expression = "user is a superuser OR project tag manage")
@DeletePermission(expression = "user is a superuser OR project tag manage")
@Paginate(defaultPageSize = 10000, maxPageSize = 10000)
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "project_tag")
public class ProjectTag extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tag_id")
    private String tagId;

    @Column(name = "tag_value")
    private String value;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;
}
