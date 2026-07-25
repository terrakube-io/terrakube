package io.terrakube.api;

import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.github.GitHubRepositoryDiscoveryService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class VcsRepositoryDiscoveryGitHubTests extends ServerApplicationTests {

    @Autowired
    GitHubRepositoryDiscoveryService gitHubRepositoryDiscoveryService;

    private static String privateKeyPem;

    @BeforeAll
    static void generateAppPrivateKey() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    private Vcs oauthVcs() {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setAccessToken("oauth-token-abc");
        vcs.setApiUrl("http://localhost:" + wireMockServer.port());
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcs;
    }

    private Vcs appVcs() {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setClientId("app-client-id");
        vcs.setPrivateKey(privateKeyPem);
        vcs.setApiUrl("http://localhost:" + wireMockServer.port());
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcs;
    }

    // --- OAuth connection ---

    @Test
    void oauthListGroupsIncludesPersonalAccountAndOrgs() {
        stubFor(get(urlPathEqualTo("/user"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"login\":\"octocat\"}")));
        stubFor(get(urlPathEqualTo("/user/orgs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"login\":\"my-org\"}]")));

        List<VcsGroupSummary> groups = gitHubRepositoryDiscoveryService.listGroups(oauthVcs());

        assertThat(groups).extracting(VcsGroupSummary::getId).containsExactlyInAnyOrder("@me", "my-org");
        assertThat(groups).filteredOn(g -> g.getId().equals("my-org")).extracting(VcsGroupSummary::getName)
                .containsExactly("my-org");
    }

    @Test
    void oauthListRepositoriesBrowsesOrgRepos() {
        String body = "[{\"name\":\"repo1\",\"full_name\":\"my-org/repo1\",\"owner\":{\"login\":\"my-org\"}," +
                "\"clone_url\":\"https://github.com/my-org/repo1.git\",\"private\":true,\"default_branch\":\"main\"}]";
        stubFor(get(urlPathEqualTo("/orgs/my-org/repos"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

        VcsRepositoryPage page = gitHubRepositoryDiscoveryService.listRepositories(oauthVcs(), "my-org", "", 1);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getFullName()).isEqualTo("my-org/repo1");
        assertThat(page.getItems().get(0).getUrl()).isEqualTo("https://github.com/my-org/repo1.git");
        assertThat(page.getItems().get(0).isPrivateRepo()).isTrue();
        assertThat(page.isHasMore()).isFalse();
    }

    @Test
    void oauthListRepositoriesUsesSearchApiWhenFiltering() {
        String body = "{\"total_count\":120,\"items\":[{\"name\":\"repo1\",\"full_name\":\"my-org/repo1\"," +
                "\"owner\":{\"login\":\"my-org\"},\"clone_url\":\"https://github.com/my-org/repo1.git\",\"private\":false}]}";
        stubFor(get(urlPathEqualTo("/search/repositories"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

        VcsRepositoryPage page = gitHubRepositoryDiscoveryService.listRepositories(oauthVcs(), "my-org", "repo", 1);

        assertThat(page.getItems()).hasSize(1);
        // total_count (120) > page(1) * PAGE_SIZE(50) so more results are available
        assertThat(page.isHasMore()).isTrue();
    }

    @Test
    void oauthListRepositoriesForPersonalAccountUsesUserRepos() {
        stubFor(get(urlPathEqualTo("/user/repos"))
                .withQueryParam("affiliation", equalTo("owner"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        VcsRepositoryPage page = gitHubRepositoryDiscoveryService.listRepositories(oauthVcs(), "@me", "", 1);

        assertThat(page.getItems()).isEmpty();
        verify(getRequestedFor(urlPathEqualTo("/user/repos")));
    }

    // --- GitHub App connection ---

    @Test
    void appListGroupsUsesInstallationsEndpoint() {
        stubFor(get(urlPathEqualTo("/app/installations"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":123,\"account\":{\"login\":\"my-app-org\"}}]")));

        List<VcsGroupSummary> groups = gitHubRepositoryDiscoveryService.listGroups(appVcs());

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getId()).isEqualTo("123");
        assertThat(groups.get(0).getName()).isEqualTo("my-app-org");
    }

    @Test
    void appListRepositoriesBrowsesInstallationRepos() {
        stubForInstallationToken();
        stubFor(get(urlPathEqualTo("/installation/repositories"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"repositories\":[{\"name\":\"repo1\",\"full_name\":\"my-app-org/repo1\"," +
                                "\"owner\":{\"login\":\"my-app-org\"},\"clone_url\":\"https://github.com/my-app-org/repo1.git\"," +
                                "\"private\":true}]}")));

        VcsRepositoryPage page = gitHubRepositoryDiscoveryService.listRepositories(appVcs(), "123", "", 1);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getFullName()).isEqualTo("my-app-org/repo1");
    }

    @Test
    void appListRepositoriesSearchScansOnePageAtATimeWithoutHardCap() {
        stubForInstallationToken();

        // Page 1: a full page (50 repos), one match -> hasMore should be true (page was full)
        StringBuilder page1 = new StringBuilder("{\"repositories\":[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) page1.append(",");
            String name = i == 25 ? "target-repo" : "other-repo-" + i;
            page1.append("{\"name\":\"").append(name).append("\",\"full_name\":\"my-app-org/").append(name)
                    .append("\",\"owner\":{\"login\":\"my-app-org\"},\"clone_url\":\"https://github.com/my-app-org/")
                    .append(name).append(".git\",\"private\":true}");
        }
        page1.append("]}");
        stubFor(get(urlPathEqualTo("/installation/repositories"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(page1.toString())));

        // Page 2: not full (10 repos), no match -> hasMore should be false (no more pages)
        StringBuilder page2 = new StringBuilder("{\"repositories\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) page2.append(",");
            page2.append("{\"name\":\"page2-repo-").append(i).append("\",\"full_name\":\"my-app-org/page2-repo-").append(i)
                    .append("\",\"owner\":{\"login\":\"my-app-org\"},\"clone_url\":\"https://github.com/my-app-org/page2-repo-")
                    .append(i).append(".git\",\"private\":true}");
        }
        page2.append("]}");
        stubFor(get(urlPathEqualTo("/installation/repositories"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(page2.toString())));

        VcsRepositoryPage firstPage = gitHubRepositoryDiscoveryService.listRepositories(appVcs(), "123", "target", 1);
        assertThat(firstPage.getItems()).extracting(r -> r.getName()).containsExactly("target-repo");
        assertThat(firstPage.isHasMore()).isTrue();

        VcsRepositoryPage secondPage = gitHubRepositoryDiscoveryService.listRepositories(appVcs(), "123", "target", 2);
        assertThat(secondPage.getItems()).isEmpty();
        assertThat(secondPage.isHasMore()).isFalse();
    }

    private void stubForInstallationToken() {
        stubFor(get(urlPathEqualTo("/app/installations/123"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"account\":{\"login\":\"my-app-org\"}}")));
        stubFor(post(urlPathEqualTo("/app/installations/123/access_tokens"))
                .willReturn(aResponse().withStatus(HttpStatus.CREATED.value()).withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"installation-token-abc\"}")));
    }
}
