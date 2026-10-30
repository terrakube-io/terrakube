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
 * <p>Jobs started by a webhook event follow {@link #vcsViolation(Workspace, Source, String, String)} instead. Schedules
 * and run triggers always use the workspace branch and do not go through this rule.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceBranchPolicy {

    /** What started a run that a VCS event asked for. */
    public enum Source {
        /** A push to a branch, seen as a webhook or by polling. */
        PUSH,
        /** A pull request event or a comment command on one. The code is the pull request's, not the branch's. */
        PULL_REQUEST,
        /** A release or tag the workspace manager chose to run through a release event. */
        RELEASE
    }

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
     * The same rule for a run that a webhook event started. The commit comes from the event, so unlike a run created
     * by a user it is allowed, but the code has to come from the configured branch:
     * <ul>
     *   <li>a push counts only on the configured branch, so a push to another branch that an event matches can
     *       still plan;</li>
     *   <li>a pull request or a comment command on one can only plan, whatever its branch is called, because a
     *       pull request can come from a fork whose branch has the same name;</li>
     *   <li>a release runs a template that the workspace manager attached to the release event, so it is allowed
     *       here. Anyone who can publish a release can point it at any commit, so the executor checks that the
     *       commit is on the configured branch before an apply or destroy step runs.</li>
     * </ul>
     *
     * @return why the run is refused, or empty when it is allowed
     */
    public Optional<String> vcsViolation(Workspace workspace, Source source, String branch, String templateReference) {
        if (workspace == null || !workspace.isRestrictApplyToConfiguredBranch() || source == Source.RELEASE) {
            return Optional.empty();
        }
        if (tclService.isTemplatePlanOnly(templateReference)) {
            return Optional.empty();
        }

        String configured = configuredBranch(workspace);
        if (source == Source.PULL_REQUEST) {
            return Optional.of("Workspace " + workspace.getName() + " restricts apply and destroy to its configured "
                    + "branch, " + configured + ", so a pull request can only run a plan-only template. "
                    + "Merge the pull request to apply it.");
        }
        String pushed = isBlank(branch) ? "" : WorkspaceBranches.shortName(branch.trim());
        if (!pushed.equals(configured)) {
            return Optional.of("Workspace " + workspace.getName() + " only allows plan runs on branches other than "
                    + configured + ". The webhook event for branch " + pushed + " uses a template that can change "
                    + "infrastructure, so no run was started.");
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
