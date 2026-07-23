package io.terrakube.api.plugin.vcs.discovery;

import io.terrakube.api.plugin.vcs.discovery.azdevops.AzDevOpsRepositoryDiscoveryService;
import io.terrakube.api.plugin.vcs.discovery.bitbucket.BitbucketRepositoryDiscoveryService;
import io.terrakube.api.plugin.vcs.discovery.github.GitHubRepositoryDiscoveryService;
import io.terrakube.api.plugin.vcs.discovery.gitlab.GitLabRepositoryDiscoveryService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VcsRepositoryDiscoveryFacadeTest {

    GitHubRepositoryDiscoveryService gitHubService;
    GitLabRepositoryDiscoveryService gitLabService;
    BitbucketRepositoryDiscoveryService bitbucketService;
    AzDevOpsRepositoryDiscoveryService azDevOpsService;
    VcsRepositoryDiscoveryFacade subject;

    @BeforeEach
    void setup() {
        gitHubService = mock(GitHubRepositoryDiscoveryService.class);
        gitLabService = mock(GitLabRepositoryDiscoveryService.class);
        bitbucketService = mock(BitbucketRepositoryDiscoveryService.class);
        azDevOpsService = mock(AzDevOpsRepositoryDiscoveryService.class);
        subject = new VcsRepositoryDiscoveryFacade(gitHubService, gitLabService, bitbucketService, azDevOpsService);
    }

    private Vcs vcs(VcsType type) {
        Vcs vcs = new Vcs();
        vcs.setVcsType(type);
        return vcs;
    }

    @Test
    void dispatchesGitHubToGitHubProvider() {
        Vcs vcs = vcs(VcsType.GITHUB);
        List<VcsGroupSummary> expected = Collections.singletonList(VcsGroupSummary.builder().id("a").name("a").build());
        when(gitHubService.listGroups(vcs)).thenReturn(expected);

        assertThat(subject.listGroups(vcs)).isEqualTo(expected);
    }

    @Test
    void dispatchesGitLabToGitLabProvider() {
        Vcs vcs = vcs(VcsType.GITLAB);
        VcsRepositoryPage expected = VcsRepositoryPage.builder().items(Collections.emptyList()).page(1).hasMore(false).build();
        when(gitLabService.listRepositories(vcs, "g", "s", 1)).thenReturn(expected);

        assertThat(subject.listRepositories(vcs, "g", "s", 1)).isEqualTo(expected);
    }

    @Test
    void dispatchesBitbucketToBitbucketProvider() {
        Vcs vcs = vcs(VcsType.BITBUCKET);

        subject.listGroups(vcs);

        verify(bitbucketService).listGroups(vcs);
    }

    @Test
    void dispatchesAzureDevOpsToAzureDevOpsProvider() {
        Vcs vcs = vcs(VcsType.AZURE_DEVOPS);

        subject.listGroups(vcs);

        verify(azDevOpsService).listGroups(vcs);
    }

    @Test
    void throwsForVcsTypesWithNoDiscoveryProvider() {
        assertThatThrownBy(() -> subject.listGroups(vcs(VcsType.PUBLIC)))
                .isInstanceOf(VcsDiscoveryNotSupportedException.class);

        assertThatThrownBy(() -> subject.listGroups(vcs(VcsType.AZURE_SP_MI)))
                .isInstanceOf(VcsDiscoveryNotSupportedException.class);
    }
}
