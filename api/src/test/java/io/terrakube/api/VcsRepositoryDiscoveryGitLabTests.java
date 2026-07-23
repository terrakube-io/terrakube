package io.terrakube.api;

import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.gitlab.GitLabRepositoryDiscoveryService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class VcsRepositoryDiscoveryGitLabTests extends ServerApplicationTests {

    @Autowired
    GitLabRepositoryDiscoveryService gitLabRepositoryDiscoveryService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    private Vcs gitlabVcs() {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);
        vcs.setAccessToken("gitlab-token-abc");
        vcs.setApiUrl("http://localhost:" + wireMockServer.port() + "/api/v4");
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcs;
    }

    @Test
    void listGroupsIncludesPersonalNamespaceAndRealGroups() {
        stubFor(get(urlPathEqualTo("/api/v4/groups"))
                .withQueryParam("membership", equalTo("true"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":42,\"full_path\":\"my-group\"}]")));

        List<VcsGroupSummary> groups = gitLabRepositoryDiscoveryService.listGroups(gitlabVcs());

        assertThat(groups).extracting(VcsGroupSummary::getId).containsExactlyInAnyOrder("@me", "42");
        assertThat(groups).filteredOn(g -> g.getId().equals("42")).extracting(VcsGroupSummary::getName)
                .containsExactly("my-group");
    }

    @Test
    void listRepositoriesForGroupMapsFieldsAndNoMorePages() {
        String body = "[{\"name\":\"repo1\",\"path_with_namespace\":\"my-group/repo1\"," +
                "\"namespace\":{\"full_path\":\"my-group\"},\"http_url_to_repo\":\"https://gitlab.com/my-group/repo1.git\"," +
                "\"visibility\":\"private\",\"default_branch\":\"main\"}]";
        stubFor(get(urlPathEqualTo("/api/v4/groups/42/projects"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

        VcsRepositoryPage page = gitLabRepositoryDiscoveryService.listRepositories(gitlabVcs(), "42", "", 1);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getFullName()).isEqualTo("my-group/repo1");
        assertThat(page.getItems().get(0).getGroup()).isEqualTo("my-group");
        assertThat(page.getItems().get(0).getUrl()).isEqualTo("https://gitlab.com/my-group/repo1.git");
        assertThat(page.getItems().get(0).isPrivateRepo()).isTrue();
        assertThat(page.isHasMore()).isFalse();
    }

    @Test
    void listRepositoriesHasMoreWhenNextPageHeaderPresent() {
        stubFor(get(urlPathEqualTo("/api/v4/groups/42/projects"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("X-Next-Page", "2").withBody("[]")));

        VcsRepositoryPage page = gitLabRepositoryDiscoveryService.listRepositories(gitlabVcs(), "42", "", 1);

        assertThat(page.isHasMore()).isTrue();
    }

    @Test
    void listRepositoriesForPersonalNamespaceUsesOwnedProjects() {
        stubFor(get(urlPathEqualTo("/api/v4/projects"))
                .withQueryParam("owned", equalTo("true"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        VcsRepositoryPage page = gitLabRepositoryDiscoveryService.listRepositories(gitlabVcs(), "@me", "", 1);

        assertThat(page.getItems()).isEmpty();
        verify(getRequestedFor(urlPathEqualTo("/api/v4/projects")).withQueryParam("owned", equalTo("true")));
    }

    @Test
    void listRepositoriesPassesSearchQueryParam() {
        stubFor(get(urlPathEqualTo("/api/v4/groups/42/projects"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        gitLabRepositoryDiscoveryService.listRepositories(gitlabVcs(), "42", "terraform", 1);

        verify(getRequestedFor(urlPathEqualTo("/api/v4/groups/42/projects")).withQueryParam("search", equalTo("terraform")));
    }
}
