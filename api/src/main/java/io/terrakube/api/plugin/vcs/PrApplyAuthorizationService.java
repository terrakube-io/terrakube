package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * Decides whether the author of a "terrakube apply" comment may apply. The job that comment creates
 * auto-approves, so it skips the template's approval step and its team: without this check, anyone
 * who can comment on the pull request could apply it.
 *
 * <p>The author is checked against the repository, since a VCS login does not map to a Terrakube
 * user: GitHub needs write access or more, GitLab the Developer role or more. When the provider
 * cannot be asked, the apply is refused. Bitbucket and Azure DevOps are not checked yet; their
 * comments keep working as before and are logged.
 */
@Slf4j
@Service
@AllArgsConstructor
public class PrApplyAuthorizationService {

    /** The legacy "permission" field GitHub returns; "maintain" is reported as "write". */
    static final Set<String> GITHUB_APPLY_PERMISSIONS = Set.of("admin", "write");

    static final int GITLAB_DEVELOPER_ACCESS_LEVEL = 30;

    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;

    /**
     * @return why the comment author may not apply, or empty when the apply can go ahead
     */
    public Optional<String> refusalReason(Workspace workspace, WebhookResult webhookResult) {
        Vcs vcs = workspace.getVcs();
        String author = webhookResult.getCreatedBy();
        if (vcs == null || vcs.getVcsType() == null) {
            log.warn("PR apply comment on workspace {} has no VCS connection to check the author against",
                    workspace.getName());
            return Optional.empty();
        }

        return switch (vcs.getVcsType()) {
            case GITHUB -> checkGitHub(workspace, vcs, author);
            case GITLAB -> checkGitLab(workspace, author, webhookResult.getCommentAuthorId());
            default -> {
                log.warn("PR apply comment by {} on workspace {} is not checked: no permission check for {}",
                        author, workspace.getName(), vcs.getVcsType());
                yield Optional.empty();
            }
        };
    }

    private Optional<String> checkGitHub(Workspace workspace, Vcs vcs, String author) {
        if (author == null || author.isBlank()) {
            return Optional.of(cannotCheck("unknown user"));
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
        String who = author == null || author.isBlank() ? "unknown user" : author;
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

    private static String notAllowed(String author, String requirement) {
        return "`%s` cannot apply from a comment: it needs %s. Apply this plan from the Terrakube UI, or ask someone who has it."
                .formatted(author, requirement);
    }

    private static String cannotCheck(String author) {
        return "Could not check whether `%s` may apply from a comment, so the apply was not started. Apply this plan from the Terrakube UI."
                .formatted(author);
    }
}
