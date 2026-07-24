package io.terrakube.api.plugin.vcs.discovery.bitbucket;

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

// Bitbucket Cloud and Bitbucket Server (on-premise/Data Center) are both stored with vcsType=BITBUCKET
// (see AddVCS.tsx) and are only distinguishable by apiUrl: Cloud always uses api.bitbucket.org, while
// Server instances point at a customer-hosted host with a completely different REST API shape.
@Slf4j
@Service
public class BitbucketRepositoryDiscoveryService implements VcsRepositoryDiscoveryProvider {

    private static final int PAGE_SIZE = 50;
    private final ObjectMapper objectMapper;

    public BitbucketRepositoryDiscoveryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private boolean isCloud(Vcs vcs) {
        return vcs.getApiUrl() != null && vcs.getApiUrl().contains("api.bitbucket.org");
    }

    @Override
    public List<VcsGroupSummary> listGroups(Vcs vcs) {
        return isCloud(vcs) ? listCloudWorkspaces(vcs) : listServerProjects(vcs, null);
    }

    @Override
    public VcsRepositoryPage listRepositories(Vcs vcs, String group, String search, int page) {
        return isCloud(vcs) ? listCloudRepositories(vcs, group, search, page) : listServerRepositories(vcs, group, search, page);
    }

    private List<VcsGroupSummary> listCloudWorkspaces(Vcs vcs) {
        String url = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/workspaces")
                .queryParam("role", "member")
                .queryParam("pagelen", 100)
                .toUriString();
        JsonNode response = callApi(url, vcs.getAccessToken()).orElse(null);
        List<VcsGroupSummary> groups = new ArrayList<>();
        if (response != null) {
            for (JsonNode workspace : response.path("values")) {
                groups.add(VcsGroupSummary.builder()
                        .id(workspace.path("slug").asText())
                        .name(workspace.path("name").asText())
                        .build());
            }
        }
        return groups;
    }

    private VcsRepositoryPage listCloudRepositories(Vcs vcs, String group, String search, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/repositories/" + group)
                .queryParam("pagelen", PAGE_SIZE)
                .queryParam("page", page)
                .queryParam("sort", "name");
        if (search != null && !search.isBlank()) {
            builder.queryParam("q", "name~\"" + search.replace("\"", "") + "\"");
        }
        JsonNode response = callApi(builder.toUriString(), vcs.getAccessToken()).orElse(null);
        List<VcsRepositorySummary> items = new ArrayList<>();
        if (response != null) {
            for (JsonNode repo : response.path("values")) {
                items.add(VcsRepositorySummary.builder()
                        .name(repo.path("name").asText())
                        .fullName(repo.path("full_name").asText())
                        .group(group)
                        .url(extractCloudCloneUrl(repo))
                        .privateRepo(repo.path("is_private").asBoolean())
                        .defaultBranch(repo.path("mainbranch").path("name").asText(null))
                        .build());
            }
        }
        boolean hasMore = response != null && response.hasNonNull("next");
        return VcsRepositoryPage.builder().items(items).page(page).hasMore(hasMore).build();
    }

    private String extractCloudCloneUrl(JsonNode repo) {
        for (JsonNode link : repo.path("links").path("clone")) {
            if ("https".equals(link.path("name").asText())) {
                return link.path("href").asText();
            }
        }
        return repo.path("links").path("html").path("href").asText() + ".git";
    }

    private List<VcsGroupSummary> listServerProjects(Vcs vcs, String search) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/projects")
                .queryParam("limit", 100);
        if (search != null && !search.isBlank()) {
            builder.queryParam("name", search);
        }
        JsonNode response = callApi(builder.toUriString(), vcs.getAccessToken()).orElse(null);
        List<VcsGroupSummary> groups = new ArrayList<>();
        if (response != null) {
            for (JsonNode project : response.path("values")) {
                groups.add(VcsGroupSummary.builder()
                        .id(project.path("key").asText())
                        .name(project.path("name").asText())
                        .build());
            }
        }
        return groups;
    }

    private VcsRepositoryPage listServerRepositories(Vcs vcs, String group, String search, int page) {
        int start = (Math.max(page, 1) - 1) * PAGE_SIZE;
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(vcs.getApiUrl() + "/projects/" + group + "/repos")
                .queryParam("start", start)
                .queryParam("limit", PAGE_SIZE);
        if (search != null && !search.isBlank()) {
            builder.queryParam("name", search);
        }
        JsonNode response = callApi(builder.toUriString(), vcs.getAccessToken()).orElse(null);
        List<VcsRepositorySummary> items = new ArrayList<>();
        if (response != null) {
            for (JsonNode repo : response.path("values")) {
                items.add(VcsRepositorySummary.builder()
                        .name(repo.path("name").asText())
                        .fullName(group + "/" + repo.path("slug").asText())
                        .group(group)
                        .url(extractServerCloneUrl(repo))
                        .privateRepo(true)
                        .defaultBranch(null)
                        .build());
            }
        }
        boolean hasMore = response != null && !response.path("isLastPage").asBoolean(true);
        return VcsRepositoryPage.builder().items(items).page(page).hasMore(hasMore).build();
    }

    private String extractServerCloneUrl(JsonNode repo) {
        for (JsonNode link : repo.path("links").path("clone")) {
            if ("http".equalsIgnoreCase(link.path("name").asText()) || "https".equalsIgnoreCase(link.path("name").asText())) {
                return link.path("href").asText();
            }
        }
        if (repo.path("links").path("clone").isArray() && repo.path("links").path("clone").size() > 0) {
            return repo.path("links").path("clone").get(0).path("href").asText();
        }
        return "";
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
            log.error("Error calling Bitbucket API {}: {}", url, e.getMessage());
        }
        return Optional.empty();
    }
}
