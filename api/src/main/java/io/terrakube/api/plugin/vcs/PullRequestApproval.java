package io.terrakube.api.plugin.vcs;

/**
 * Whether a pull request is approved under the VCS's own rules, read together with the head commit
 * the decision applies to.
 *
 * @param approved whether the VCS considers the pull request approved
 * @param decision the provider's own wording, for logs and the refusal notice (for example GitHub's
 *                 {@code REVIEW_REQUIRED}); never null
 * @param headSha  the head commit of the pull request when the decision was read, or null if unknown
 */
public record PullRequestApproval(boolean approved, String decision, String headSha) {
}
