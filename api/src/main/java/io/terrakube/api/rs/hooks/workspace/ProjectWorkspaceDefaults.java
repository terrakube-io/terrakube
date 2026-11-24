package io.terrakube.api.rs.hooks.workspace;

import io.terrakube.api.rs.ExecutionMode;
import io.terrakube.api.rs.project.Project;

/**
 * What a project says a new workspace in it should start with. Workspaces are created through two doors, the Elide
 * API and the remote (go-tfe) API, and the second does not run Elide's hooks, so the rule lives here for both:
 * a value the request sent wins, then the project's default, then the organization's.
 */
public final class ProjectWorkspaceDefaults {

    private ProjectWorkspaceDefaults() {
    }

    /** The project's default execution mode, or null when it has none. */
    public static ExecutionMode executionMode(Project project) {
        if (project == null || project.getDefaultExecutionMode() == null || project.getDefaultExecutionMode().isBlank()) {
            return null;
        }
        try {
            return ExecutionMode.valueOf(project.getDefaultExecutionMode().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The project's default Terraform version, or null when it has none. */
    public static String terraformVersion(Project project) {
        if (project == null || project.getDefaultTerraformVersion() == null
                || project.getDefaultTerraformVersion().isBlank()) {
            return null;
        }
        return project.getDefaultTerraformVersion().trim();
    }

    /** The project's default template id, or null when it has none. */
    public static String template(Project project) {
        if (project == null || project.getDefaultTemplate() == null || project.getDefaultTemplate().isBlank()) {
            return null;
        }
        return project.getDefaultTemplate().trim();
    }
}
