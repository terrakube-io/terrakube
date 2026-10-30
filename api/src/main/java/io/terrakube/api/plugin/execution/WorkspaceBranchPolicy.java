package io.terrakube.api.plugin.execution;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The opt-in "restrict apply to the configured branch" rule of a workspace.
 *
 * <p>With the setting on, a job created through the API or the Terraform CLI can only change infrastructure
 * from the workspace branch. A plan-only template can run on any branch. A job that names a commit is refused
 * too: the executor fetches that commit by id, so it could come from a different branch and run under the
 * name of the configured one.
 *
 * <p>Jobs created by server-side code (webhooks, schedules, run triggers) do not go through this rule.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceBranchPolicy {

    private final TclService tclService;

    /**
     * @return why the job is refused, or empty when it is allowed
     */
    public Optional<String> violation(Workspace workspace, String overrideBranch, String commitId,
                                      String templateReference) {
        if (workspace == null || !workspace.isRestrictApplyToConfiguredBranch()) {
            return Optional.empty();
        }
        // A template that fails to load counts as one that can change infrastructure.
        if (tclService.isTemplatePlanOnly(templateReference)) {
            return Optional.empty();
        }

        String configured = configuredBranch(workspace);
        String effective = isBlank(overrideBranch) ? configured : WorkspaceBranches.shortName(overrideBranch);
        if (!effective.equals(configured)) {
            // No quotes around the names: Elide HTML-escapes error messages, which would show as &#39;.
            return Optional.of("Workspace " + workspace.getName() + " only allows plan runs on branches other than "
                    + configured + ". Use a plan-only template for branch " + effective
                    + ", or run this template on " + configured + ".");
        }
        if (!isBlank(commitId)) {
            return Optional.of("Workspace " + workspace.getName() + " only allows a run that can change "
                    + "infrastructure to use the tip of branch " + configured + ". Remove the commit id or use a "
                    + "plan-only template.");
        }
        return Optional.empty();
    }

    /**
     * What to tell the Terraform CLI user whose run is refused. The CLI runs its local configuration, which is
     * never the workspace branch, so the generic message about branches would not make sense there.
     */
    public String cliMessage(Workspace workspace) {
        return "Workspace " + workspace.getName() + " restricts apply and destroy to its configured branch, "
                + configuredBranch(workspace) + ", so the Terraform CLI can only run terraform plan here. "
                + "terraform apply, terraform destroy and terraform plan -destroy are refused. "
                + "Merge the change to " + configuredBranch(workspace) + " to apply it.";
    }

    private static String configuredBranch(Workspace workspace) {
        return WorkspaceBranches.shortName(WorkspaceBranches.firstBranch(workspace.getBranch()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
