package io.terrakube.api.rs.policy;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.UUID;

@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "policy_attachment")
@ReadPermission(expression = "team view policy attachment")
@CreatePermission(expression = "team manage policy attachment")
@UpdatePermission(expression = "team manage policy attachment")
@DeletePermission(expression = "team manage policy attachment")
public class PolicyAttachment extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "policy_set_id")
    private PolicySet policySet;

    @ManyToOne
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne
    @JoinColumn(name = "tag_id")
    private Tag tag;
}
