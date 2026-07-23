package io.terrakube.api.plugin.vcs.discovery;

import java.util.List;

import io.terrakube.api.rs.vcs.Vcs;

public interface VcsRepositoryDiscoveryProvider {

    List<VcsGroupSummary> listGroups(Vcs vcs);

    VcsRepositoryPage listRepositories(Vcs vcs, String group, String search, int page);
}
