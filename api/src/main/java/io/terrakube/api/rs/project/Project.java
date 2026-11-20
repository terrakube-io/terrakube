package io.terrakube.api.rs.project;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.hooks.project.ProjectDeleteGuardHook;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.project.tag.ProjectTag;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "user is a superuser OR project read filter")
@CreatePermission(expression = "team manage project")
@UpdatePermission(expression = "team manage project OR team project admin manages project access field")
@DeletePermission(expression = "team manage project")
// Reads are filtered in SQL (ProjectReadFilter), so explicit pagination and sorting work. The high default keeps
// callers that read without paging (Terraform provider, terrakube-go, CLI) returning every project as before.
@Paginate(defaultPageSize = 10000, maxPageSize = 10000)
// PREFLUSH, not PRECOMMIT: the flush is where the database would refuse the delete with its own, unhelpful, error.
@LifeCycleHookBinding(operation = LifeCycleHookBinding.Operation.DELETE, phase = LifeCycleHookBinding.TransactionPhase.PREFLUSH, hook = ProjectDeleteGuardHook.class)
@Include
@Getter
@Setter
@Entity(name = "project")
public class Project extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank(message = "Project name is required")
    @Size(max = 256, message = "Project name must be 256 characters or fewer")
    @Column(name = "name")
    private String name;

    @Size(max = 256, message = "Project description must be 256 characters or fewer")
    @Column(name = "description")
    private String description;

    // The team that owns the project, by name. Null when nobody has claimed it.
    @Size(max = 256, message = "Project owner must be 256 characters or fewer")
    @Column(name = "owner_team")
    private String ownerTeam;

    // Overrides the organization default for the tab this project opens on. Null means use the default.
    @Pattern(regexp = LandingTab.PATTERN, message = LandingTab.MESSAGE)
    @Column(name = "landing_tab")
    private String landingTab;

    // Defaults for workspaces created in this project. Null means the organization's default (or the caller's value).
    @Size(max = 32, message = "Default Terraform version must be 32 characters or fewer")
    @Column(name = "default_terraform_version")
    private String defaultTerraformVersion;

    // The workflow template (by id) a new workspace in this project starts with, when the request names none.
    @Size(max = 36, message = "Default template must be a template id")
    @Column(name = "default_template")
    private String defaultTemplate;

    @Pattern(regexp = "remote|local", message = "Default execution mode must be remote or local")
    @Column(name = "default_execution_mode")
    private String defaultExecutionMode;

    @ManyToOne
    private Organization organization;

    @UpdatePermission(expression = "team manage project OR team project admin manages project access field")
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProjectAccess> projectAccess = new ArrayList<>();

    // Tags copied onto workspaces created in or moved into the project.
    @UpdatePermission(expression = "team manage project OR team project admin manages project access field")
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProjectTag> projectTag = new ArrayList<>();
}
