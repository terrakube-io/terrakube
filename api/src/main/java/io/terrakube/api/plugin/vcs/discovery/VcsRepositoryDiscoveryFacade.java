package io.terrakube.api.plugin.vcs.discovery;

import java.util.List;

import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.vcs.discovery.azdevops.AzDevOpsRepositoryDiscoveryService;
import io.terrakube.api.plugin.vcs.discovery.bitbucket.BitbucketRepositoryDiscoveryService;
import io.terrakube.api.plugin.vcs.discovery.github.GitHubRepositoryDiscoveryService;
import io.terrakube.api.plugin.vcs.discovery.gitlab.GitLabRepositoryDiscoveryService;
import io.terrakube.api.rs.vcs.Vcs;

@Service
public class VcsRepositoryDiscoveryFacade {

    private final GitHubRepositoryDiscoveryService gitHubService;
    private final GitLabRepositoryDiscoveryService gitLabService;
    private final BitbucketRepositoryDiscoveryService bitbucketService;
    private final AzDevOpsRepositoryDiscoveryService azDevOpsService;

    public VcsRepositoryDiscoveryFacade(GitHubRepositoryDiscoveryService gitHubService,
            GitLabRepositoryDiscoveryService gitLabService,
            BitbucketRepositoryDiscoveryService bitbucketService,
            AzDevOpsRepositoryDiscoveryService azDevOpsService) {
        this.gitHubService = gitHubService;
        this.gitLabService = gitLabService;
        this.bitbucketService = bitbucketService;
        this.azDevOpsService = azDevOpsService;
    }

    public List<VcsGroupSummary> listGroups(Vcs vcs) {
        return provider(vcs).listGroups(vcs);
    }

    public VcsRepositoryPage listRepositories(Vcs vcs, String group, String search, int page) {
        return provider(vcs).listRepositories(vcs, group, search, page);
    }

    private VcsRepositoryDiscoveryProvider provider(Vcs vcs) {
        switch (vcs.getVcsType()) {
            case GITHUB:
                return gitHubService;
            case GITLAB:
                return gitLabService;
            case BITBUCKET:
                return bitbucketService;
            case AZURE_DEVOPS:
                return azDevOpsService;
            default:
                throw new VcsDiscoveryNotSupportedException("Repository discovery is not supported for this VCS connection type");
        }
    }
}
