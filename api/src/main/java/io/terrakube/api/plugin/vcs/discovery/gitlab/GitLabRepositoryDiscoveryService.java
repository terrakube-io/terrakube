package io.terrakube.api.plugin.vcs.discovery.gitlab;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryDiscoveryProvider;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositorySummary;
import io.terrakube.api.rs.vcs.Vcs;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GitLabRepositoryDiscoveryService implements VcsRepositoryDiscoveryProvider {

    private static final int PAGE_SIZE = 50;
    private static final String PERSONAL_NAMESPACE_GROUP = "@me";
    private final ObjectMapper objectMapper;

    public GitLabRepositoryDiscoveryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<VcsGroupSummary> listGroups(Vcs vcs) {
        List<VcsGroupSummary> groups = new ArrayList<>();
        groups.add(VcsGroupSummary.builder().id(PERSONAL_NAMESPACE_GROUP).name("My Projects").build());

        String url = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/groups")
                .queryParam("membership", true)
                .queryParam("per_page", 100)
                .toUriString();
        JsonNode response = callApi(url, vcs.getAccessToken()).orElse(null);
        if (response != null) {
            for (JsonNode group : response) {
                groups.add(VcsGroupSummary.builder()
                        .id(group.path("id").asText())
                        .name(group.path("full_path").asText())
                        .build());
            }
        }
        return groups;
    }

    @Override
    public VcsRepositoryPage listRepositories(Vcs vcs, String group, String search, int page) {
        UriComponentsBuilder builder;
        if (PERSONAL_NAMESPACE_GROUP.equals(group)) {
            builder = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/projects")
                    .queryParam("owned", true);
        } else {
            builder = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/groups/" + group + "/projects")
                    .queryParam("include_subgroups", true);
        }
        builder.queryParam("per_page", PAGE_SIZE).queryParam("page", page).queryParam("order_by", "name");
        if (search != null && !search.isBlank()) {
            builder.queryParam("search", search);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + vcs.getAccessToken());
        try {
            RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
            ResponseEntity<String> response = restTemplate.exchange(builder.toUriString(), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            List<VcsRepositorySummary> items = new ArrayList<>();
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                for (JsonNode project : objectMapper.readTree(response.getBody())) {
                    items.add(toSummary(project));
                }
            }
            String nextPage = response.getHeaders().getFirst("X-Next-Page");
            boolean hasMore = nextPage != null && !nextPage.isBlank();
            return VcsRepositoryPage.builder().items(items).page(page).hasMore(hasMore).build();
        } catch (Exception e) {
            log.error("Error calling GitLab API {}: {}", builder.toUriString(), e.getMessage());
            return VcsRepositoryPage.builder().items(new ArrayList<>()).page(page).hasMore(false).build();
        }
    }

    private VcsRepositorySummary toSummary(JsonNode project) {
        return VcsRepositorySummary.builder()
                .name(project.path("name").asText())
                .fullName(project.path("path_with_namespace").asText())
                .group(project.path("namespace").path("full_path").asText())
                .url(project.path("http_url_to_repo").asText())
                .privateRepo(!"public".equals(project.path("visibility").asText()))
                .defaultBranch(project.path("default_branch").asText(null))
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
            log.error("Error calling GitLab API {}: {}", url, e.getMessage());
        }
        return Optional.empty();
    }
}
