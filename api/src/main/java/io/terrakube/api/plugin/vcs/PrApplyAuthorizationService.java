package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.PrApplyRequirement;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Decides whether the author of a "terrakube apply" comment may apply. The job that comment creates
 * auto-approves, so it skips the template's approval step and its team: without this check, anyone
 * who can comment on the pull request could apply it.
 *
 * <p>The author is checked against the repository, since a VCS login does not map to a Terrakube
 * user: GitHub needs write access or more, GitLab the Developer role or more. When the provider
 * cannot be asked, the apply is refused. Bitbucket and Azure DevOps are not checked yet; with the
 * default {@link PrApplyRequirement#WRITE} their comments keep working as before and are logged, and
 * when an approval is required they are refused, since nothing can confirm it.
 *
 * <p>With {@link PrApplyRequirement#APPROVED} (on the pull request trigger, or as the organization
 * minimum), the pull request must also be approved under the VCS's own rules. The decision is read
 * for the commit the apply will run on: the job checks out the head commit read when the comment
 * arrived, so if the head has moved since, the apply is refused and has to be asked for again.
 * Whether an approval given on an earlier commit still counts is the VCS's rule (dismiss stale
 * approvals on GitHub, reset approvals on push on GitLab).
 */
@Slf4j
@Service
@AllArgsConstructor
public class PrApplyAuthorizationService {

    /** The legacy "permission" field GitHub returns; "maintain" is reported as "write". */
    static final Set<String> GITHUB_APPLY_PERMISSIONS = Set.of("admin", "write");

    static final int GITLAB_DEVELOPER_ACCESS_LEVEL = 30;

    /**
     * A GitHub or GitLab username that can be @-mentioned in the notice. Anything else, such as the
     * "unknown user" placeholder or a bot login, is quoted as code instead.
     */
    static final Pattern MENTIONABLE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,254}$");

    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;

    /**
     * @return why the comment author may not apply, or empty when the apply can go ahead
     */
    public Optional<String> refusalReason(Workspace workspace, WebhookResult webhookResult,
            PrApplyRequirement triggerRequirement) {
        PrApplyRequirement requirement = PrApplyRequirement.strictest(triggerRequirement,
                workspace.getOrganization() != null ? workspace.getOrganization().getPrApplyMinRequirement() : null);
        Vcs vcs = workspace.getVcs();
        String author = webhookResult.getCreatedBy();
        if (vcs == null || vcs.getVcsType() == null) {
            if (requirement == PrApplyRequirement.APPROVED) {
                // Fail closed: an approval was asked for and nothing can confirm it.
                log.warn("Refusing PR apply comment by {} on workspace {}: approval required but no VCS connection",
                        author, workspace.getName());
                return Optional.of(cannotCheck(whoOf(author)));
            }
            log.warn("PR apply comment on workspace {} has no VCS connection to check the author against",
                    workspace.getName());
            return Optional.empty();
        }

        return switch (vcs.getVcsType()) {
            case GITHUB -> checkGitHub(workspace, vcs, author)
                    .or(() -> checkApproval(requirement, workspace, webhookResult, author,
                            () -> gitHubWebhookService.getPullRequestApproval(vcs, workspace.getSource(),
                                    prNumber(webhookResult))));
            case GITLAB -> checkGitLab(workspace, author, webhookResult.getCommentAuthorId())
                    .or(() -> checkApproval(requirement, workspace, webhookResult, author,
                            () -> gitLabWebhookService.getMergeRequestApproval(workspace, prNumber(webhookResult))));
            default -> {
                if (requirement == PrApplyRequirement.APPROVED) {
                    // Fail closed: approvals cannot be read from this provider yet.
                    log.warn("Refusing PR apply comment by {} on workspace {}: approval required, not checked for {}",
                            author, workspace.getName(), vcs.getVcsType());
                    yield Optional.of(approvalNotSupported(whoOf(author)));
                }
                log.warn("PR apply comment by {} on workspace {} is not checked: no permission check for {}",
                        author, workspace.getName(), vcs.getVcsType());
                yield Optional.empty();
            }
        };
    }

    private static String whoOf(String author) {
        return author == null || author.isBlank() ? "unknown user" : author;
    }

    private static int prNumber(WebhookResult webhookResult) {
        return webhookResult.getPrNumber() != null ? webhookResult.getPrNumber().intValue() : 0;
    }

    /**
     * The pull request must be approved, and the decision must be for the commit the job will check out
     * (the comment's commit): an approval of an earlier head does not let a later head be applied.
     */
    private Optional<String> checkApproval(PrApplyRequirement requirement, Workspace workspace,
            WebhookResult webhookResult, String author, Supplier<Optional<PullRequestApproval>> approvalLookup) {
        if (requirement != PrApplyRequirement.APPROVED) {
            return Optional.empty();
        }
        String who = whoOf(author);
        String commit = webhookResult.getCommit();
        if (prNumber(webhookResult) <= 0 || commit == null || commit.isBlank()) {
            log.error("PR apply comment by {} on workspace {} has no pull request number or commit to check", who,
                    workspace.getName());
            return Optional.of(cannotCheck(who));
        }
        Optional<PullRequestApproval> approval = approvalLookup.get();
        if (approval.isEmpty()) {
            return Optional.of(cannotCheck(who));
        }
        if (approval.get().headSha() == null || approval.get().headSha().isBlank()) {
            // Without the head the decision cannot be tied to the commit; asking again would not help.
            log.error("PR apply comment by {} on workspace {}: the provider returned no head commit", who,
                    workspace.getName());
            return Optional.of(cannotCheck(who));
        }
        if (!commit.equals(approval.get().headSha())) {
            log.info("Refusing PR apply comment by {} on workspace {}: head moved from {} to {}", who,
                    workspace.getName(), commit, approval.get().headSha());
            return Optional.of(headMoved(who));
        }
        if (!approval.get().approved()) {
            log.info("Refusing PR apply comment by {} on workspace {}: pull request is not approved ({})", who,
                    workspace.getName(), approval.get().decision());
            return Optional.of(notApproved(who, approval.get().decision()));
        }
        return Optional.empty();
    }

    private Optional<String> checkGitHub(Workspace workspace, Vcs vcs, String author) {
        if (author == null || author.isBlank()) {
            return Optional.of(cannotCheck(whoOf(author)));
        }
        Optional<String> permission = gitHubWebhookService.getRepositoryPermission(vcs, workspace.getSource(), author);
        if (permission.isEmpty()) {
            return Optional.of(cannotCheck(author));
        }
        if (!GITHUB_APPLY_PERMISSIONS.contains(permission.get())) {
            log.info("Refusing PR apply comment by {} on workspace {}: repository permission is {}", author,
                    workspace.getName(), permission.get());
            return Optional.of(notAllowed(author, "write access to the repository"));
        }
        return Optional.empty();
    }

    private Optional<String> checkGitLab(Workspace workspace, String author, String authorId) {
        String who = whoOf(author);
        if (authorId == null || authorId.isBlank()) {
            return Optional.of(cannotCheck(who));
        }
        Optional<Integer> accessLevel = gitLabWebhookService.getProjectAccessLevel(workspace, authorId);
        if (accessLevel.isEmpty()) {
            return Optional.of(cannotCheck(who));
        }
        if (accessLevel.get() < GITLAB_DEVELOPER_ACCESS_LEVEL) {
            log.info("Refusing PR apply comment by {} on workspace {}: project access level is {}", who,
                    workspace.getName(), accessLevel.get());
            return Optional.of(notAllowed(who, "the Developer role or higher in the project"));
        }
        return Optional.empty();
    }

    /**
     * Mentions the comment author, so the notice reaches them; quotes what is not a plain username.
     * Backticks and line breaks are dropped from a quoted name, so it cannot end the code span and add
     * its own markdown to the comment.
     */
    static String mention(String author) {
        if (author != null && MENTIONABLE.matcher(author).matches()) {
            return "@" + author;
        }
        String quoted = String.valueOf(author).replaceAll("[`\\r\\n]", "");
        return "`" + quoted + "`";
    }

    private static String notAllowed(String author, String requirement) {
        return "%s cannot apply from a comment: it needs %s. Apply this plan from the Terrakube UI, or ask someone who has it."
                .formatted(mention(author), requirement);
    }

    private static String notApproved(String author, String decision) {
        return "%s cannot apply from a comment: this workspace requires the pull request to be approved first (current state: %s). Comment again once it is approved, or apply this plan from the Terrakube UI."
                .formatted(mention(author), decision);
    }

    private static String approvalNotSupported(String author) {
        return "%s cannot apply from a comment: this workspace requires an approved pull request, and Terrakube cannot check approvals on this VCS provider yet. Apply this plan from the Terrakube UI."
                .formatted(mention(author));
    }

    private static String headMoved(String author) {
        return "The pull request changed while the apply requested by %s was being checked, so it was not started. Review the latest commit and comment again."
                .formatted(mention(author));
    }

    private static String cannotCheck(String author) {
        return "Could not check whether %s may apply from a comment, so the apply was not started. If this was a temporary error, comment again; otherwise apply this plan from the Terrakube UI."
                .formatted(mention(author));
    }
}
