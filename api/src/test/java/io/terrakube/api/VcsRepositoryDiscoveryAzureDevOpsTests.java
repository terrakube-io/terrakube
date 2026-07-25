package io.terrakube.api;

import io.terrakube.api.plugin.vcs.discovery.VcsDiscoveryNotSupportedException;
import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.azdevops.AzDevOpsRepositoryDiscoveryService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

public class VcsRepositoryDiscoveryAzureDevOpsTests extends ServerApplicationTests {

    @Autowired
    AzDevOpsRepositoryDiscoveryService azDevOpsRepositoryDiscoveryService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
        // WireMock can't stand in for the real app.vssps.visualstudio.com hostname
        ReflectionTestUtils.setField(azDevOpsRepositoryDiscoveryService, "profileEndpoint",
                "http://localhost:" + wireMockServer.port());
    }

    private Vcs oauthVcs() {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.AZURE_DEVOPS);
        vcs.setName("azure-devops-oauth");
        vcs.setDescription("azure-devops-oauth");
        vcs.setClientId("123");
        vcs.setClientSecret("123");
        vcs.setAccessToken("azdevops-token-abc");
        vcs.setApiUrl("http://localhost:" + wireMockServer.port());
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcsRepository.save(vcs);
    }

    private Vcs managedIdentityVcs() {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.AZURE_SP_MI);
        vcs.setName("azure-devops-managed-identity");
        vcs.setDescription("azure-devops-managed-identity");
        vcs.setClientId("123");
        vcs.setClientSecret("123");
        vcs.setApiUrl("http://localhost:" + wireMockServer.port());
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcsRepository.save(vcs);
    }

    @Test
    void managedIdentityConnectionIsNotSupportedForDiscovery() {
        Vcs vcs = managedIdentityVcs();

        assertThatThrownBy(() -> azDevOpsRepositoryDiscoveryService.listGroups(vcs))
                .isInstanceOf(VcsDiscoveryNotSupportedException.class);
        assertThatThrownBy(() -> azDevOpsRepositoryDiscoveryService.listRepositories(vcs, "my-org", "", 1))
                .isInstanceOf(VcsDiscoveryNotSupportedException.class);
    }

    @Test
    void listGroupsResolvesOrganizationsThroughProfileAndAccountsApi() {
        stubFor(get(urlPathEqualTo("/_apis/profile/profiles/me"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"member-guid\"}")));
        stubFor(get(urlPathEqualTo("/_apis/accounts"))
                .withQueryParam("memberId", equalTo("member-guid"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"value\":[{\"accountName\":\"my-org\"}]}")));

        List<VcsGroupSummary> groups = azDevOpsRepositoryDiscoveryService.listGroups(oauthVcs());

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getId()).isEqualTo("my-org");
        assertThat(groups.get(0).getName()).isEqualTo("my-org");
    }

    @Test
    void listRepositoriesMapsFieldsFiltersAndPaginatesLocally() {
        StringBuilder body = new StringBuilder("{\"value\":[");
        for (int i = 0; i < 60; i++) {
            if (i > 0) body.append(",");
            String name = i == 5 ? "terraform-infra" : "other-repo-" + i;
            body.append("{\"name\":\"").append(name).append("\",\"remoteUrl\":\"https://dev.azure.com/my-org/proj/_git/")
                    .append(name).append("\",\"defaultBranch\":\"refs/heads/main\",\"project\":{\"name\":\"proj\"}}");
        }
        body.append("]}");
        stubFor(get(urlPathEqualTo("/my-org/_apis/git/repositories"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body.toString())));

        VcsRepositoryPage filtered = azDevOpsRepositoryDiscoveryService.listRepositories(oauthVcs(), "my-org", "terraform", 1);
        assertThat(filtered.getItems()).hasSize(1);
        assertThat(filtered.getItems().get(0).getName()).isEqualTo("terraform-infra");
        assertThat(filtered.getItems().get(0).getFullName()).isEqualTo("my-org/proj/terraform-infra");
        assertThat(filtered.getItems().get(0).getUrl()).isEqualTo("https://dev.azure.com/my-org/proj/_git/terraform-infra");
        assertThat(filtered.isHasMore()).isFalse();

        VcsRepositoryPage firstPage = azDevOpsRepositoryDiscoveryService.listRepositories(oauthVcs(), "my-org", "", 1);
        assertThat(firstPage.getItems()).hasSize(50);
        assertThat(firstPage.isHasMore()).isTrue();

        VcsRepositoryPage secondPage = azDevOpsRepositoryDiscoveryService.listRepositories(oauthVcs(), "my-org", "", 2);
        assertThat(secondPage.getItems()).hasSize(10);
        assertThat(secondPage.isHasMore()).isFalse();
    }

    @Test
    void listRepositoriesCachesTheFullOrgResponseBetweenCalls() {
        Vcs vcs = oauthVcs();
        stubFor(get(urlPathEqualTo("/my-org/_apis/git/repositories"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"value\":[{\"name\":\"repo1\",\"remoteUrl\":\"https://dev.azure.com/my-org/proj/_git/repo1\"," +
                                "\"project\":{\"name\":\"proj\"}}]}")));

        azDevOpsRepositoryDiscoveryService.listRepositories(vcs, "my-org", "", 1);
        azDevOpsRepositoryDiscoveryService.listRepositories(vcs, "my-org", "repo", 1);

        // Two logical searches within the cache TTL should only hit Azure once
        verify(exactly(1), getRequestedFor(urlPathEqualTo("/my-org/_apis/git/repositories")));
    }
}
