package io.terrakube.api;

import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.bitbucket.BitbucketRepositoryDiscoveryService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Bitbucket Cloud and Server share vcsType=BITBUCKET and are only distinguished by apiUrl
 * (see BitbucketRepositoryDiscoveryService#isCloud). Since WireMock can't stand in for the
 * literal "api.bitbucket.org" host, the Cloud-shaped HTTP methods are exercised directly via
 * ReflectionTestUtils, while the Server path is exercised through the normal public dispatch
 * (a plain localhost apiUrl is correctly detected as "server").
 */
public class VcsRepositoryDiscoveryBitbucketTests extends ServerApplicationTests {

    @Autowired
    BitbucketRepositoryDiscoveryService bitbucketRepositoryDiscoveryService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    private Vcs bitbucketVcs(String apiUrl) {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.BITBUCKET);
        vcs.setAccessToken("bitbucket-token-abc");
        vcs.setApiUrl(apiUrl);
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcs;
    }

    @Test
    void detectsCloudVsServerFromApiUrl() {
        Vcs cloudVcs = bitbucketVcs("https://api.bitbucket.org/2.0");
        Vcs serverVcs = bitbucketVcs("https://bitbucket.mycompany.com/rest/api/1.0");

        boolean cloud = ReflectionTestUtils.invokeMethod(bitbucketRepositoryDiscoveryService, "isCloud", cloudVcs);
        boolean server = ReflectionTestUtils.invokeMethod(bitbucketRepositoryDiscoveryService, "isCloud", serverVcs);

        assertThat(cloud).isTrue();
        assertThat(server).isFalse();
    }

    // --- Bitbucket Cloud API shape ---

    @Test
    void cloudListGroupsMapsWorkspaces() {
        Vcs vcs = bitbucketVcs("http://localhost:" + wireMockServer.port());
        stubFor(get(urlPathEqualTo("/workspaces"))
                .withQueryParam("role", equalTo("member"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[{\"slug\":\"my-ws\",\"name\":\"My Workspace\"}]}")));

        List<VcsGroupSummary> groups = ReflectionTestUtils.invokeMethod(bitbucketRepositoryDiscoveryService, "listCloudWorkspaces", vcs);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getId()).isEqualTo("my-ws");
        assertThat(groups.get(0).getName()).isEqualTo("My Workspace");
    }

    @Test
    void cloudListRepositoriesMapsFieldsAndPagination() {
        Vcs vcs = bitbucketVcs("http://localhost:" + wireMockServer.port());
        String body = "{\"values\":[{\"name\":\"repo1\",\"full_name\":\"my-ws/repo1\",\"is_private\":true," +
                "\"mainbranch\":{\"name\":\"main\"},\"links\":{\"clone\":[{\"name\":\"https\",\"href\":\"https://bitbucket.org/my-ws/repo1.git\"}]}}]," +
                "\"next\":\"https://api.bitbucket.org/2.0/repositories/my-ws?page=2\"}";
        stubFor(get(urlPathEqualTo("/repositories/my-ws"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

        VcsRepositoryPage page = ReflectionTestUtils.invokeMethod(bitbucketRepositoryDiscoveryService, "listCloudRepositories", vcs, "my-ws", "", 1);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getFullName()).isEqualTo("my-ws/repo1");
        assertThat(page.getItems().get(0).getUrl()).isEqualTo("https://bitbucket.org/my-ws/repo1.git");
        assertThat(page.getItems().get(0).isPrivateRepo()).isTrue();
        assertThat(page.isHasMore()).isTrue();
    }

    @Test
    void cloudListRepositoriesPassesSearchQuery() {
        Vcs vcs = bitbucketVcs("http://localhost:" + wireMockServer.port());
        stubFor(get(urlPathEqualTo("/repositories/my-ws"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"values\":[]}")));

        ReflectionTestUtils.invokeMethod(bitbucketRepositoryDiscoveryService, "listCloudRepositories", vcs, "my-ws", "terraform", 1);

        verify(getRequestedFor(urlPathEqualTo("/repositories/my-ws")).withQueryParam("q", containing("terraform")));
    }

    // --- Bitbucket Server (on-premise) API shape ---

    @Test
    void serverListGroupsMapsProjects() {
        Vcs vcs = bitbucketVcs("http://localhost:" + wireMockServer.port() + "/rest/api/1.0");
        stubFor(get(urlPathEqualTo("/rest/api/1.0/projects"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[{\"key\":\"PRJ\",\"name\":\"My Project\"}]}")));

        List<VcsGroupSummary> groups = bitbucketRepositoryDiscoveryService.listGroups(vcs);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getId()).isEqualTo("PRJ");
        assertThat(groups.get(0).getName()).isEqualTo("My Project");
    }

    @Test
    void serverListRepositoriesMapsFieldsAndPagination() {
        Vcs vcs = bitbucketVcs("http://localhost:" + wireMockServer.port() + "/rest/api/1.0");
        String body = "{\"values\":[{\"name\":\"repo1\",\"slug\":\"repo1\"," +
                "\"links\":{\"clone\":[{\"name\":\"http\",\"href\":\"http://bitbucket.mycompany.com/scm/PRJ/repo1.git\"}]}}]," +
                "\"isLastPage\":false}";
        stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/PRJ/repos"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

        VcsRepositoryPage page = bitbucketRepositoryDiscoveryService.listRepositories(vcs, "PRJ", "", 1);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getFullName()).isEqualTo("PRJ/repo1");
        assertThat(page.getItems().get(0).getUrl()).isEqualTo("http://bitbucket.mycompany.com/scm/PRJ/repo1.git");
        assertThat(page.isHasMore()).isTrue();
    }

    @Test
    void serverListRepositoriesIsLastPageMeansNoMore() {
        Vcs vcs = bitbucketVcs("http://localhost:" + wireMockServer.port() + "/rest/api/1.0");
        stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/PRJ/repos"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[],\"isLastPage\":true}")));

        VcsRepositoryPage page = bitbucketRepositoryDiscoveryService.listRepositories(vcs, "PRJ", "", 1);

        assertThat(page.isHasMore()).isFalse();
    }

    @Test
    void serverListRepositoriesPassesNameFilterAndStartOffset() {
        Vcs vcs = bitbucketVcs("http://localhost:" + wireMockServer.port() + "/rest/api/1.0");
        stubFor(get(urlPathEqualTo("/rest/api/1.0/projects/PRJ/repos"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"values\":[],\"isLastPage\":true}")));

        bitbucketRepositoryDiscoveryService.listRepositories(vcs, "PRJ", "terraform", 3);

        verify(getRequestedFor(urlPathEqualTo("/rest/api/1.0/projects/PRJ/repos"))
                .withQueryParam("name", equalTo("terraform"))
                .withQueryParam("start", equalTo("100")));
    }
}
