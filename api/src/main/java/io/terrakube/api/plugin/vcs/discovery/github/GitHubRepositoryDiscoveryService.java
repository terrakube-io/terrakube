package io.terrakube.api.plugin.vcs.discovery.github;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.vcs.discovery.VcsDiscoveryNotSupportedException;
import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryDiscoveryProvider;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositorySummary;
import io.terrakube.api.plugin.vcs.provider.github.GitHubTokenService;
import io.terrakube.api.rs.vcs.Vcs;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GitHubRepositoryDiscoveryService implements VcsRepositoryDiscoveryProvider {

    private static final int PAGE_SIZE = 50;
    private static final String PERSONAL_ACCOUNT_GROUP = "@me";

    private final GitHubTokenService gitHubTokenService;
    private final ObjectMapper objectMapper;

    public GitHubRepositoryDiscoveryService(GitHubTokenService gitHubTokenService, ObjectMapper objectMapper) {
        this.gitHubTokenService = gitHubTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<VcsGroupSummary> listGroups(Vcs vcs) {
        if (isOAuth(vcs)) {
            return listOAuthGroups(vcs);
        }
        return listAppInstallationGroups(vcs);
    }

    @Override
    public VcsRepositoryPage listRepositories(Vcs vcs, String group, String search, int page) {
        if (isOAuth(vcs)) {
            return listOAuthRepositories(vcs, group, search, page);
        }
        return listAppRepositories(vcs, group, search, page);
    }

    private boolean isOAuth(Vcs vcs) {
        return vcs.getAccessToken() != null && !vcs.getAccessToken().isEmpty();
    }

    private List<VcsGroupSummary> listOAuthGroups(Vcs vcs) {
        List<VcsGroupSummary> groups = new ArrayList<>();

        JsonNode me = callApi(vcs.getApiUrl() + "/user", vcs.getAccessToken()).orElse(null);
        if (me != null) {
            groups.add(VcsGroupSummary.builder().id(PERSONAL_ACCOUNT_GROUP).name(me.path("login").asText() + " (personal)").build());
        }

        JsonNode orgs = callApi(vcs.getApiUrl() + "/user/orgs?per_page=100", vcs.getAccessToken()).orElse(null);
        if (orgs != null) {
            for (JsonNode org : orgs) {
                String login = org.path("login").asText();
                groups.add(VcsGroupSummary.builder().id(login).name(login).build());
            }
        }

        return groups;
    }

    private VcsRepositoryPage listOAuthRepositories(Vcs vcs, String group, String search, int page) {
        String url;
        if (search != null && !search.isBlank()) {
            String scopedGroup = PERSONAL_ACCOUNT_GROUP.equals(group) ? null : group;
            String query = search + " in:name" + (scopedGroup != null ? " user:" + scopedGroup : "");
            url = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/search/repositories")
                    .queryParam("q", query)
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", page)
                    .toUriString();
            JsonNode response = callApi(url, vcs.getAccessToken()).orElse(null);
            if (response == null) {
                return emptyPage(page);
            }
            List<VcsRepositorySummary> items = new ArrayList<>();
            for (JsonNode repo : response.path("items")) {
                items.add(toSummary(repo));
            }
            return VcsRepositoryPage.builder().items(items).page(page)
                    .hasMore(response.path("total_count").asInt() > page * PAGE_SIZE).build();
        }

        if (PERSONAL_ACCOUNT_GROUP.equals(group)) {
            url = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/user/repos")
                    .queryParam("affiliation", "owner")
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", page)
                    .toUriString();
        } else {
            url = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/orgs/" + group + "/repos")
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", page)
                    .toUriString();
        }

        JsonNode response = callApi(url, vcs.getAccessToken()).orElse(null);
        if (response == null) {
            return emptyPage(page);
        }
        List<VcsRepositorySummary> items = new ArrayList<>();
        for (JsonNode repo : response) {
            items.add(toSummary(repo));
        }
        return VcsRepositoryPage.builder().items(items).page(page).hasMore(items.size() == PAGE_SIZE).build();
    }

    private List<VcsGroupSummary> listAppInstallationGroups(Vcs vcs) {
        if (vcs.getPrivateKey() == null || vcs.getPrivateKey().isEmpty()) {
            throw new VcsDiscoveryNotSupportedException("Repository discovery is not supported for this VCS connection");
        }
        String jws = generateAppJwt(vcs);
        List<VcsGroupSummary> groups = new ArrayList<>();
        JsonNode response = callAppApi(vcs.getApiUrl() + "/app/installations", jws).orElse(null);
        if (response != null) {
            for (JsonNode installation : response) {
                groups.add(VcsGroupSummary.builder()
                        .id(installation.path("id").asText())
                        .name(installation.path("account").path("login").asText())
                        .build());
            }
        }
        return groups;
    }

    private VcsRepositoryPage listAppRepositories(Vcs vcs, String installationId, String search, int page) {
        String jws = generateAppJwt(vcs);
        JsonNode installation = callAppApi(vcs.getApiUrl() + "/app/installations/" + installationId, jws).orElse(null);
        String owner = installation != null ? installation.path("account").path("login").asText() : "";

        String installationToken;
        try {
            installationToken = gitHubTokenService.getInstallationToken(installationId, vcs.getApiUrl(), jws, owner);
        } catch (Exception e) {
            log.error("Error fetching GitHub App installation token for installation {}: {}", installationId, e.getMessage());
            return emptyPage(page);
        }

        if (search == null || search.isBlank()) {
            String url = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/installation/repositories")
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", page)
                    .toUriString();
            JsonNode response = callApi(url, installationToken).orElse(null);
            List<VcsRepositorySummary> items = new ArrayList<>();
            if (response != null) {
                for (JsonNode repo : response.path("repositories")) {
                    items.add(toSummary(repo));
                }
            }
            return VcsRepositoryPage.builder().items(items).page(page).hasMore(items.size() == PAGE_SIZE).build();
        }

        // The installation-repositories endpoint doesn't support server-side search. Rather than
        // scanning a fixed number of pages up front (which would silently miss matches in larger
        // installations), each "page" the UI asks for maps directly to one provider page that we
        // filter here; hasMore reflects whether that provider page was full, so repeatedly clicking
        // "Load more" keeps scanning forward until the whole installation has been covered.
        String url = UriComponentsBuilder.fromHttpUrl(vcs.getApiUrl() + "/installation/repositories")
                .queryParam("per_page", PAGE_SIZE)
                .queryParam("page", page)
                .toUriString();
        JsonNode response = callApi(url, installationToken).orElse(null);
        List<VcsRepositorySummary> matches = new ArrayList<>();
        int count = 0;
        if (response != null) {
            String lowerSearch = search.toLowerCase();
            for (JsonNode repo : response.path("repositories")) {
                count++;
                if (repo.path("name").asText("").toLowerCase().contains(lowerSearch)) {
                    matches.add(toSummary(repo));
                }
            }
        }
        return VcsRepositoryPage.builder().items(matches).page(page).hasMore(count == PAGE_SIZE).build();
    }

    private String generateAppJwt(Vcs vcs) {
        try {
            return gitHubTokenService.generateAppJwt(vcs);
        } catch (Exception e) {
            log.error("Error generating GitHub App JWT for vcs {}: {}", vcs.getId(), e.getMessage());
            throw new VcsDiscoveryNotSupportedException("Unable to authenticate with GitHub App credentials");
        }
    }

    private VcsRepositorySummary toSummary(JsonNode repo) {
        return VcsRepositorySummary.builder()
                .name(repo.path("name").asText())
                .fullName(repo.path("full_name").asText())
                .group(repo.path("owner").path("login").asText())
                .url(repo.path("clone_url").asText(repo.path("html_url").asText() + ".git"))
                .privateRepo(repo.path("private").asBoolean())
                .defaultBranch(repo.path("default_branch").asText(null))
                .build();
    }

    private VcsRepositoryPage emptyPage(int page) {
        return VcsRepositoryPage.builder().items(new ArrayList<>()).page(page).hasMore(false).build();
    }

    private java.util.Optional<JsonNode> callApi(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("Authorization", "Bearer " + token);
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return execute(url, headers);
    }

    private java.util.Optional<JsonNode> callAppApi(String url, String jws) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("Authorization", "Bearer " + jws);
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return execute(url, headers);
    }

    private java.util.Optional<JsonNode> execute(String url, HttpHeaders headers) {
        try {
            RestTemplate restTemplate = gitHubTokenService.getRestTemplateWithProxy();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return java.util.Optional.of(objectMapper.readTree(response.getBody()));
            }
        } catch (Exception e) {
            log.error("Error calling GitHub API {}: {}", url, e.getMessage());
        }
        return java.util.Optional.empty();
    }
}
