package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.PrApplyRequirement;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrApplyAuthorizationServiceTest {

    private static final String SOURCE = "https://github.com/owner/repo.git";

    GitHubWebhookService gitHub;
    GitLabWebhookService gitLab;
    PrApplyAuthorizationService subject;

    @BeforeEach
    void setup() {
        gitHub = mock(GitHubWebhookService.class);
        gitLab = mock(GitLabWebhookService.class);
        subject = new PrApplyAuthorizationService(gitHub, gitLab);
    }

    private static Workspace workspace(VcsType type) {
        Vcs vcs = new Vcs();
        vcs.setVcsType(type);
        Workspace workspace = new Workspace();
        workspace.setName("network");
        workspace.setSource(SOURCE);
        workspace.setVcs(vcs);
        return workspace;
    }

    private static WebhookResult comment(String author, String authorId) {
        WebhookResult result = new WebhookResult();
        result.setPrComment(true);
        result.setCommentCommand("apply");
        result.setCreatedBy(author);
        result.setCommentAuthorId(authorId);
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "write"})
    void gitHubAuthorWithWriteAccessMayApply(String permission) {
        Workspace workspace = workspace(VcsType.GITHUB);
        when(gitHub.getRepositoryPermission(workspace.getVcs(), SOURCE, "maintainer"))
                .thenReturn(Optional.of(permission));

        assertThat(subject.refusalReason(workspace, comment("maintainer", null), PrApplyRequirement.WRITE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "none"})
    void gitHubAuthorWithoutWriteAccessIsRefused(String permission) {
        Workspace workspace = workspace(VcsType.GITHUB);
        when(gitHub.getRepositoryPermission(workspace.getVcs(), SOURCE, "reader"))
                .thenReturn(Optional.of(permission));

        assertThat(subject.refusalReason(workspace, comment("reader", null), PrApplyRequirement.WRITE))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("@reader cannot apply")
                        .contains("write access"));
    }

    @Test
    void gitHubApplyIsRefusedWhenThePermissionCannotBeRead() {
        Workspace workspace = workspace(VcsType.GITHUB);
        when(gitHub.getRepositoryPermission(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(subject.refusalReason(workspace, comment("someone", null), PrApplyRequirement.WRITE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("Could not check whether @someone"));
    }

    @Test
    void gitHubApplyWithoutAnAuthorIsRefusedWithoutAskingGitHub() {
        Workspace workspace = workspace(VcsType.GITHUB);

        assertThat(subject.refusalReason(workspace, comment("", null), PrApplyRequirement.WRITE)).isPresent();
        verify(gitHub, never()).getRepositoryPermission(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 40, 50})
    void gitLabDeveloperOrHigherMayApply(int accessLevel) {
        Workspace workspace = workspace(VcsType.GITLAB);
        when(gitLab.getProjectAccessLevel(workspace, "42")).thenReturn(Optional.of(accessLevel));

        assertThat(subject.refusalReason(workspace, comment("dev", "42"), PrApplyRequirement.WRITE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void gitLabGuestOrReporterIsRefused(int accessLevel) {
        Workspace workspace = workspace(VcsType.GITLAB);
        when(gitLab.getProjectAccessLevel(workspace, "7")).thenReturn(Optional.of(accessLevel));

        assertThat(subject.refusalReason(workspace, comment("reporter", "7"), PrApplyRequirement.WRITE))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("@reporter cannot apply")
                        .contains("Developer role"));
    }

    @Test
    void gitLabApplyIsRefusedWhenTheAuthorIsNotAMember() {
        Workspace workspace = workspace(VcsType.GITLAB);
        when(gitLab.getProjectAccessLevel(workspace, "99")).thenReturn(Optional.empty());

        assertThat(subject.refusalReason(workspace, comment("outsider", "99"), PrApplyRequirement.WRITE)).isPresent();
    }

    @Test
    void gitLabApplyWithoutAnAuthorIdIsRefusedWithoutAskingGitLab() {
        Workspace workspace = workspace(VcsType.GITLAB);

        assertThat(subject.refusalReason(workspace, comment("dev", null), PrApplyRequirement.WRITE)).isPresent();
        verify(gitLab, never()).getProjectAccessLevel(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = VcsType.class, names = {"BITBUCKET", "AZURE_DEVOPS"})
    void providersWithoutACheckKeepApplying(VcsType type) {
        assertThat(subject.refusalReason(workspace(type), comment("anyone", null), PrApplyRequirement.WRITE)).isEmpty();
        verifyNoInteractions(gitHub, gitLab);
    }

    @Nested
    class ApprovedRequirement {

        private static final String HEAD = "a1b2c3";

        private WebhookResult approvedComment(String author, String authorId) {
            WebhookResult result = comment(author, authorId);
            result.setPrNumber(12);
            result.setCommit(HEAD);
            return result;
        }

        private Workspace gitHubWriter() {
            Workspace workspace = workspace(VcsType.GITHUB);
            when(gitHub.getRepositoryPermission(workspace.getVcs(), SOURCE, "maintainer"))
                    .thenReturn(Optional.of("write"));
            return workspace;
        }

        private Workspace gitLabDeveloper() {
            Workspace workspace = workspace(VcsType.GITLAB);
            when(gitLab.getProjectAccessLevel(workspace, "42")).thenReturn(Optional.of(30));
            return workspace;
        }

        @Test
        void writeRequirementDoesNotAskForTheReviewDecision() {
            Workspace workspace = gitHubWriter();

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null),
                    PrApplyRequirement.WRITE)).isEmpty();
            verify(gitHub, never()).getPullRequestApproval(any(), any(), anyInt());
        }

        @Test
        void nullRequirementCountsAsWrite() {
            Workspace workspace = gitHubWriter();

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null), null)).isEmpty();
            verify(gitHub, never()).getPullRequestApproval(any(), any(), anyInt());
        }

        @Test
        void gitHubApprovedPullRequestOnTheCommentsCommitMayApply() {
            Workspace workspace = gitHubWriter();
            when(gitHub.getPullRequestApproval(workspace.getVcs(), SOURCE, 12))
                    .thenReturn(Optional.of(new PullRequestApproval(true, "APPROVED", HEAD)));

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null),
                    PrApplyRequirement.APPROVED)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"REVIEW_REQUIRED", "CHANGES_REQUESTED", "no review required"})
        void gitHubPullRequestThatIsNotApprovedIsRefused(String decision) {
            Workspace workspace = gitHubWriter();
            when(gitHub.getPullRequestApproval(workspace.getVcs(), SOURCE, 12))
                    .thenReturn(Optional.of(new PullRequestApproval(false, decision, HEAD)));

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null),
                    PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains("@maintainer cannot apply")
                            .contains("approved")
                            .contains(decision));
        }

        @Test
        void approvalOfAnotherHeadDoesNotAllowTheApply() {
            Workspace workspace = gitHubWriter();
            when(gitHub.getPullRequestApproval(workspace.getVcs(), SOURCE, 12))
                    .thenReturn(Optional.of(new PullRequestApproval(true, "APPROVED", "newer-head")));

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null),
                    PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("The pull request changed"));
        }

        @Test
        void gitHubApplyIsRefusedWhenTheReviewDecisionCannotBeRead() {
            Workspace workspace = gitHubWriter();
            when(gitHub.getPullRequestApproval(any(), any(), anyInt())).thenReturn(Optional.empty());

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null),
                    PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("Could not check whether @maintainer"));
        }

        @Test
        void commentWithoutACommitIsRefusedWithoutAskingGitHub() {
            Workspace workspace = gitHubWriter();
            WebhookResult result = approvedComment("maintainer", null);
            result.setCommit(null);

            assertThat(subject.refusalReason(workspace, result, PrApplyRequirement.APPROVED)).isPresent();
            verify(gitHub, never()).getPullRequestApproval(any(), any(), anyInt());
        }

        @Test
        void authorWithoutWriteAccessIsRefusedBeforeTheReviewDecisionIsRead() {
            Workspace workspace = workspace(VcsType.GITHUB);
            when(gitHub.getRepositoryPermission(workspace.getVcs(), SOURCE, "reader")).thenReturn(Optional.of("read"));

            assertThat(subject.refusalReason(workspace, approvedComment("reader", null), PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("write access"));
            verify(gitHub, never()).getPullRequestApproval(any(), any(), anyInt());
        }

        @Test
        void organizationMinimumRaisesTheTriggerRequirement() {
            Workspace workspace = gitHubWriter();
            Organization organization = new Organization();
            organization.setPrApplyMinRequirement(PrApplyRequirement.APPROVED);
            workspace.setOrganization(organization);
            when(gitHub.getPullRequestApproval(workspace.getVcs(), SOURCE, 12))
                    .thenReturn(Optional.of(new PullRequestApproval(false, "REVIEW_REQUIRED", HEAD)));

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null),
                    PrApplyRequirement.WRITE)).isPresent();
        }

        @Test
        void organizationMinimumDoesNotLowerTheTriggerRequirement() {
            Workspace workspace = gitHubWriter();
            Organization organization = new Organization();
            organization.setPrApplyMinRequirement(PrApplyRequirement.WRITE);
            workspace.setOrganization(organization);
            when(gitHub.getPullRequestApproval(workspace.getVcs(), SOURCE, 12))
                    .thenReturn(Optional.of(new PullRequestApproval(false, "REVIEW_REQUIRED", HEAD)));

            assertThat(subject.refusalReason(workspace, approvedComment("maintainer", null),
                    PrApplyRequirement.APPROVED)).isPresent();
        }

        @Test
        void gitLabApprovedMergeRequestMayApply() {
            Workspace workspace = gitLabDeveloper();
            when(gitLab.getMergeRequestApproval(workspace, 12))
                    .thenReturn(Optional.of(new PullRequestApproval(true, "approved", HEAD)));

            assertThat(subject.refusalReason(workspace, approvedComment("dev", "42"), PrApplyRequirement.APPROVED))
                    .isEmpty();
        }

        @Test
        void gitLabMergeRequestWithApprovalsLeftIsRefused() {
            Workspace workspace = gitLabDeveloper();
            when(gitLab.getMergeRequestApproval(workspace, 12))
                    .thenReturn(Optional.of(new PullRequestApproval(false, "1 approval(s) left", HEAD)));

            assertThat(subject.refusalReason(workspace, approvedComment("dev", "42"), PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("1 approval(s) left"));
        }

        @Test
        void gitLabApplyIsRefusedWhenTheApprovalsCannotBeRead() {
            Workspace workspace = gitLabDeveloper();
            when(gitLab.getMergeRequestApproval(workspace, 12)).thenReturn(Optional.empty());

            assertThat(subject.refusalReason(workspace, approvedComment("dev", "42"), PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("Could not check whether @dev"));
        }

        @ParameterizedTest
        @EnumSource(value = VcsType.class, names = {"BITBUCKET", "AZURE_DEVOPS", "AZURE_SP_MI"})
        void providersThatCannotCheckApprovalsAreRefused(VcsType type) {
            assertThat(subject.refusalReason(workspace(type), approvedComment("anyone", null),
                    PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains("@anyone cannot apply")
                            .contains("cannot check approvals on this VCS provider"));
            verifyNoInteractions(gitHub, gitLab);
        }

        @Test
        void providersThatCannotCheckApprovalsAreRefusedByTheOrganizationMinimum() {
            Workspace workspace = workspace(VcsType.BITBUCKET);
            Organization organization = new Organization();
            organization.setPrApplyMinRequirement(PrApplyRequirement.APPROVED);
            workspace.setOrganization(organization);

            assertThat(subject.refusalReason(workspace, approvedComment("anyone", null), PrApplyRequirement.WRITE))
                    .isPresent();
        }

        @Test
        void aWorkspaceWithoutAVcsConnectionIsRefused() {
            Workspace workspace = workspace(VcsType.GITHUB);
            workspace.setVcs(null);

            assertThat(subject.refusalReason(workspace, approvedComment("anyone", null),
                    PrApplyRequirement.APPROVED))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("Could not check whether @anyone"));
            verifyNoInteractions(gitHub, gitLab);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "alice-smith", "alice_acme", "jane.doe"})
    void mentionsPlainUsernames(String author) {
        assertThat(PrApplyAuthorizationService.mention(author)).isEqualTo("@" + author);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown user", "github-actions[bot]", "-alice"})
    void quotesAnythingElse(String author) {
        assertThat(PrApplyAuthorizationService.mention(author)).isEqualTo("`" + author + "`");
    }

    @Test
    void dropsBackticksAndLineBreaksFromAQuotedName() {
        assertThat(PrApplyAuthorizationService.mention("a`b` @everyone\n# x"))
                .isEqualTo("`ab @everyone# x`");
    }
}
