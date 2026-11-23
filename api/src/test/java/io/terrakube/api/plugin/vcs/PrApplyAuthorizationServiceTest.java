package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        assertThat(subject.refusalReason(workspace, comment("maintainer", null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "none"})
    void gitHubAuthorWithoutWriteAccessIsRefused(String permission) {
        Workspace workspace = workspace(VcsType.GITHUB);
        when(gitHub.getRepositoryPermission(workspace.getVcs(), SOURCE, "reader"))
                .thenReturn(Optional.of(permission));

        assertThat(subject.refusalReason(workspace, comment("reader", null)))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("`reader` cannot apply")
                        .contains("write access"));
    }

    @Test
    void gitHubApplyIsRefusedWhenThePermissionCannotBeRead() {
        Workspace workspace = workspace(VcsType.GITHUB);
        when(gitHub.getRepositoryPermission(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(subject.refusalReason(workspace, comment("someone", null)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("Could not check whether `someone`"));
    }

    @Test
    void gitHubApplyWithoutAnAuthorIsRefusedWithoutAskingGitHub() {
        Workspace workspace = workspace(VcsType.GITHUB);

        assertThat(subject.refusalReason(workspace, comment("", null))).isPresent();
        verify(gitHub, never()).getRepositoryPermission(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 40, 50})
    void gitLabDeveloperOrHigherMayApply(int accessLevel) {
        Workspace workspace = workspace(VcsType.GITLAB);
        when(gitLab.getProjectAccessLevel(workspace, "42")).thenReturn(Optional.of(accessLevel));

        assertThat(subject.refusalReason(workspace, comment("dev", "42"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void gitLabGuestOrReporterIsRefused(int accessLevel) {
        Workspace workspace = workspace(VcsType.GITLAB);
        when(gitLab.getProjectAccessLevel(workspace, "7")).thenReturn(Optional.of(accessLevel));

        assertThat(subject.refusalReason(workspace, comment("reporter", "7")))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("`reporter` cannot apply")
                        .contains("Developer role"));
    }

    @Test
    void gitLabApplyIsRefusedWhenTheAuthorIsNotAMember() {
        Workspace workspace = workspace(VcsType.GITLAB);
        when(gitLab.getProjectAccessLevel(workspace, "99")).thenReturn(Optional.empty());

        assertThat(subject.refusalReason(workspace, comment("outsider", "99"))).isPresent();
    }

    @Test
    void gitLabApplyWithoutAnAuthorIdIsRefusedWithoutAskingGitLab() {
        Workspace workspace = workspace(VcsType.GITLAB);

        assertThat(subject.refusalReason(workspace, comment("dev", null))).isPresent();
        verify(gitLab, never()).getProjectAccessLevel(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = VcsType.class, names = {"BITBUCKET", "AZURE_DEVOPS"})
    void providersWithoutACheckKeepApplying(VcsType type) {
        assertThat(subject.refusalReason(workspace(type), comment("anyone", null))).isEmpty();
        verifyNoInteractions(gitHub, gitLab);
    }
}
