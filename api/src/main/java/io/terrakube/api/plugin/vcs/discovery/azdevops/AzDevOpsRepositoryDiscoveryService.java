package io.terrakube.api.plugin.vcs.discovery.azdevops;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.terrakube.api.plugin.vcs.discovery.VcsDiscoveryNotSupportedException;
import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryDiscoveryProvider;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositorySummary;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AzDevOpsRepositoryDiscoveryService implements VcsRepositoryDiscoveryProvider {

    private static final int PAGE_SIZE = 50;
    private final ObjectMapper objectMapper;

    // Overridable in tests (WireMock can't stand in for the real Microsoft hostname)
    @Value("${io.terrakube.vcs.azuredevops.profile-endpoint:https://app.vssps.visualstudio.com}")
    private String profileEndpoint;

    // Azure DevOps' "list repositories" endpoint has no server-side search or pagination, so every
    // keystroke in the UI search box would otherwise re-fetch the entire org's repo list. Cache the
    // raw per-org response briefly so browsing/searching within an org only round-trips to Azure once.
    private final Cache<String, JsonNode> repositoryListCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(200)
            .build();

    public AzDevOpsRepositoryDiscoveryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<VcsGroupSummary> listGroups(Vcs vcs) {
        requireOAuth(vcs);

        JsonNode profile = callApi(profileEndpoint + "/_apis/profile/profiles/me?api-version=6.0", vcs.getAccessToken()).orElse(null);
        if (profile == null) {
            return new ArrayList<>();
        }
        String memberId = profile.path("id").asText();

        JsonNode accounts = callApi(profileEndpoint + "/_apis/accounts?memberId=" + memberId + "&api-version=6.0",
                vcs.getAccessToken()).orElse(null);
        List<VcsGroupSummary> groups = new ArrayList<>();
        if (accounts != null) {
            for (JsonNode account : accounts.path("value")) {
                groups.add(VcsGroupSummary.builder()
                        .id(account.path("accountName").asText())
                        .name(account.path("accountName").asText())
                        .build());
            }
        }
        return groups;
    }

    @Override
    public VcsRepositoryPage listRepositories(Vcs vcs, String group, String search, int page) {
        requireOAuth(vcs);

        String cacheKey = vcs.getId() + "/" + group;
        JsonNode response = repositoryListCache.get(cacheKey, key -> {
            String url = vcs.getApiUrl() + "/" + group + "/_apis/git/repositories?api-version=6.0";
            return callApi(url, vcs.getAccessToken()).orElse(null);
        });

        List<VcsRepositorySummary> matches = new ArrayList<>();
        if (response != null) {
            String lowerSearch = search == null ? "" : search.toLowerCase();
            for (JsonNode repo : response.path("value")) {
                String name = repo.path("name").asText();
                if (lowerSearch.isEmpty() || name.toLowerCase().contains(lowerSearch)) {
                    matches.add(toSummary(repo, group));
                }
            }
        }

        int fromIndex = Math.min((Math.max(page, 1) - 1) * PAGE_SIZE, matches.size());
        int toIndex = Math.min(fromIndex + PAGE_SIZE, matches.size());
        List<VcsRepositorySummary> pageItems = new ArrayList<>(matches.subList(fromIndex, toIndex));
        return VcsRepositoryPage.builder().items(pageItems).page(page).hasMore(toIndex < matches.size()).build();
    }

    private void requireOAuth(Vcs vcs) {
        if (vcs.getVcsType() != VcsType.AZURE_DEVOPS || vcs.getAccessToken() == null || vcs.getAccessToken().isEmpty()) {
            throw new VcsDiscoveryNotSupportedException(
                    "Repository discovery requires an OAuth Azure DevOps connection; managed-identity connections must enter the repository URL manually");
        }
    }

    private VcsRepositorySummary toSummary(JsonNode repo, String org) {
        return VcsRepositorySummary.builder()
                .name(repo.path("name").asText())
                .fullName(org + "/" + repo.path("project").path("name").asText() + "/" + repo.path("name").asText())
                .group(org)
                .url(repo.path("remoteUrl").asText())
                .privateRepo(true)
                .defaultBranch(repo.path("defaultBranch").asText(null))
                .build();
    }

    private Optional<JsonNode> callApi(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        try {
            RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(objectMapper.readTree(response.getBody()));
            }
        } catch (Exception e) {
            log.error("Error calling Azure DevOps API {}: {}", url, e.getMessage());
        }
        return Optional.empty();
    }
}
