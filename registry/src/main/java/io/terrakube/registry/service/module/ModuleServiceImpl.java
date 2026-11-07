package io.terrakube.registry.service.module;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.graphql.GraphQLRequest;
import io.terrakube.client.model.graphql.GraphQLResponse;
import io.terrakube.client.model.graphql.queries.search.module.SearchOrganizationModuleResponse;
import io.terrakube.client.model.organization.module.Module;
import io.terrakube.client.model.organization.module.ModuleAttributes;
import io.terrakube.client.model.organization.module.ModuleRequest;
import io.terrakube.client.model.organization.ssh.Ssh;
import io.terrakube.client.model.organization.vcs.Vcs;
import io.terrakube.client.model.organization.vcs.github_app_token.GitHubAppToken;
import io.terrakube.client.model.response.Response;
import io.terrakube.registry.configuration.CacheConfig;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import io.terrakube.registry.service.search.CommonSearchService;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@AllArgsConstructor
@Slf4j
@Service
public class ModuleServiceImpl implements ModuleService {

    TerrakubeClient terrakubeClient;
    StorageService storageService;
    CommonSearchService commonSearchService;

    public static final String SEARCH_ORGANIZATION_MODULE_VERSION = """
        {
          organization(filter: "name==%s") {
            edges {
              node {
                id
                name
                module(filter: "name==%s;provider==%s") {
                  edges {
                    node {
                      id
                      name
                      provider
                      version(filter: "status!=removed") {
                        edges {
                          node {
                            id
                            version
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """;

    // Keeps the version inside the RSQL filter a single value; real versions never need other characters.
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}");

    public static final String SEARCH_REMOVED_MODULE_VERSION = """
        {
          organization(filter: "name==%s") {
            edges {
              node {
                id
                name
                module(filter: "name==%s;provider==%s") {
                  edges {
                    node {
                      id
                      name
                      provider
                      version(filter: "version==%s;status==removed") {
                        edges {
                          node {
                            id
                            version
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """;

    // A denylist of removed versions rather than an allowlist of the cached version list: with several
    // replicas, a new version must be downloadable even where /versions is still cached without it.
    // Caches here use the default key of all parameters: names joined with '-' collided across
    // organizations, e.g. org "a-b" module "c" and org "a" module "b-c".
    @Cacheable(cacheNames = {CacheConfig.MODULE_VERSION_REMOVED_CACHE})
    @Override
    public boolean isVersionRemoved(String organizationName, String moduleName, String providerName, String version) {
        if (!VERSION_PATTERN.matcher(version).matches()) {
            return true;
        }
        GraphQLRequest query = new GraphQLRequest();
        query.setQuery(String.format(SEARCH_REMOVED_MODULE_VERSION, organizationName, moduleName, providerName, version));
        return terrakubeClient.searchOrganizationModules(query).getData().getOrganization().getEdges().stream()
                .flatMap(organizationEdge -> organizationEdge.getNode().getModule().getEdges().stream())
                .anyMatch(moduleEdge -> !moduleEdge.getNode().getVersion().getEdges().isEmpty());
    }

    @CacheEvict(cacheNames = {CacheConfig.MODULE_VERSIONS_CACHE})
    @Override
    public void evictAvailableVersions(String organizationName, String moduleName, String providerName) {
        log.info("Evicting cached versions of module {}/{} in organization {}", moduleName, providerName, organizationName);
    }

    @Cacheable(cacheNames = {CacheConfig.MODULE_VERSIONS_CACHE}, sync = true)
    @Override
    public List<String> getAvailableVersions(String organizationName, String moduleName, String providerName) {
        log.info("Search Module versions {}/{} in Organization {}", moduleName, providerName, organizationName);

        GraphQLRequest query = new GraphQLRequest();
        query.setQuery(String.format(SEARCH_ORGANIZATION_MODULE_VERSION, organizationName, moduleName, providerName));

        AtomicInteger count= new AtomicInteger();
        List<String> definitionVersions = new ArrayList<>();

        GraphQLResponse<SearchOrganizationModuleResponse> moduleVersionResponse = terrakubeClient.searchOrganizationModules(query);
        moduleVersionResponse.getData().getOrganization().getEdges().forEach(organizationEdge -> {
            organizationEdge.getNode().getModule().getEdges().forEach(moduleEdge -> {
                moduleEdge.getNode().getVersion().getEdges().forEach(versionEdge -> {
                    definitionVersions.add(versionEdge.getNode().getVersion());
                    count.getAndIncrement();
                });
            });
        });
        log.info("Found {} versions for module {}/{}", count.get(), moduleName, providerName);

        return definitionVersions;
    }

    // sync = true coalesces concurrent cache misses for the same key into a single resolution -
    // without it, a burst of `terraform init` calls that all miss the cache at once would each
    // independently repeat the Terrakube API/VCS calls and the S3 HeadObject below.
    @Cacheable(cacheNames = {CacheConfig.MODULE_VERSION_PATH_CACHE}, sync = true)
    @Override
    public String getModuleVersionPath(String organizationName, String moduleName, String providerName, String version) {
        String moduleVersionPath = "";

        String organizationId = commonSearchService.getOrganizationId(organizationName);
        Module module = terrakubeClient.getModuleByNameAndProvider(organizationId, moduleName, providerName).getData()
                .get(0);
        String moduleSource = module.getAttributes().getSource();
        String vcsType = "PUBLIC";
        String accessToken = null;
        String vcsConnectionType = null;
        String folder = module.getAttributes().getFolder();
        String tagPrefix = module.getAttributes().getTagPrefix();

        String gitTag = Optional.ofNullable(terrakubeClient.getAllVersionsByOrganizationIdAndModuleId(organizationId, module.getId()))
                .map(Response::getData)
                .orElseGet(Collections::emptyList)
                .stream()
                .filter(moduleVersion -> version.equals(moduleVersion.getAttributes().getVersion()))
                .findFirst()
                .map(moduleVersion -> moduleVersion.getAttributes().getGitTag())
                .orElse(null);

        if (module.getRelationships().getVcs().getData() != null) {
            Vcs vcsInformation = getVcsInformation(organizationId,
                    module.getRelationships().getVcs().getData().getId());
            vcsType = vcsInformation.getAttributes().getVcsType();
            vcsConnectionType = vcsInformation.getAttributes().getConnectionType();
            accessToken = getAccessToken(vcsInformation, moduleSource);
        }

        if (module.getRelationships().getSsh().getData() != null) {
            Ssh sshInformation = getSshInformation(organizationId,
                    module.getRelationships().getSsh().getData().getId());
            vcsType = "SSH~" + sshInformation.getAttributes().getSshType();
            accessToken = sshInformation.getAttributes().getPrivateKey();
        }

        ModuleVersionDownload download = new ModuleVersionDownload(moduleSource, version, gitTag, vcsType,
                vcsConnectionType, accessToken, tagPrefix, folder);
        moduleVersionPath = storageService.searchModule(organizationName, moduleName, providerName, download);

        log.info("Registry Path: {} (resolved git tag: {})", moduleVersionPath, gitTag);
        return moduleVersionPath;
    }

    // Downloads not yet written to the API, per module. A download only adds to this map; flushDownloadCounts writes
    // each module's total with one GET and one PATCH, instead of one of each per download.
    // Counts live in memory, so a SIGKILL loses up to one interval, and two replicas flushing the same module at once
    // can overwrite each other's PATCH (read-modify-write), losing up to one interval of one replica's counts. Fine
    // for a display counter; an atomic increment in the API would remove the second loss.
    private final Map<ModuleKey, Long> pendingDownloadCounts = new ConcurrentHashMap<>();
    private static final long SHUTDOWN_FLUSH_MILLIS = 10_000;

    private record ModuleKey(String organization, String module, String provider) {
    }

    @Override
    public void updateModuleDownloadCount(String organizationName, String moduleName, String providerName) {
        pendingDownloadCounts.merge(new ModuleKey(organizationName, moduleName, providerName), 1L, Long::sum);
    }

    @Scheduled(fixedDelayString = "${io.terrakube.registry.download-count.flush-interval-seconds:30}",
            initialDelayString = "${io.terrakube.registry.download-count.flush-interval-seconds:30}",
            timeUnit = TimeUnit.SECONDS)
    public void flushDownloadCounts() {
        flushDownloadCounts(Long.MAX_VALUE);
    }

    // Also runs on graceful shutdown, so a rolling deploy does not drop the last interval's downloads. Bounded, so
    // an unreachable API delays shutdown by SHUTDOWN_FLUSH_MILLIS plus at most one client timeout, not one per module.
    @PreDestroy
    public void flushDownloadCountsOnShutdown() {
        flushDownloadCounts(System.currentTimeMillis() + SHUTDOWN_FLUSH_MILLIS);
        if (!pendingDownloadCounts.isEmpty()) {
            log.warn("Dropping {} downloads of {} module(s): not written to the API before shutdown",
                    pendingDownloadCounts.values().stream().mapToLong(Long::longValue).sum(), pendingDownloadCounts.size());
        }
    }

    /** Writes pending counts until deadline (epoch millis); modules not reached stay pending. */
    void flushDownloadCounts(long deadline) {
        for (ModuleKey key : pendingDownloadCounts.keySet()) {
            if (System.currentTimeMillis() >= deadline) {
                return;
            }
            Long delta = pendingDownloadCounts.remove(key);
            if (delta == null) {
                continue;
            }
            try {
                addDownloads(key, delta);
            } catch (Exception e) {
                // Kept for the next run: the API may be restarting.
                pendingDownloadCounts.merge(key, delta, Long::sum);
                log.warn("Could not update download count for {}/{}/{}, retrying next run: {}",
                        key.organization(), key.module(), key.provider(), e.getMessage());
            }
        }
    }

    private void addDownloads(ModuleKey key, long delta) {
        String organizationId = commonSearchService.getOrganizationId(key.organization());
        List<Module> modules = organizationId == null ? null
                : terrakubeClient.getModuleByNameAndProvider(organizationId, key.module(), key.provider()).getData();
        if (modules == null || modules.isEmpty()) {
            // Deleted since it was downloaded; retrying would never succeed.
            log.warn("Dropping {} downloads of {}/{}/{}: module not found", delta,
                    key.organization(), key.module(), key.provider());
            return;
        }
        Module module = modules.get(0);
        ModuleRequest moduleRequest = new ModuleRequest();
        ModuleAttributes moduleAttributes = new ModuleAttributes();
        moduleAttributes.setDownloadQuantity(module.getAttributes().getDownloadQuantity() + (int) delta);
        module.setAttributes(moduleAttributes);
        moduleRequest.setData(module);

        terrakubeClient.updateModule(moduleRequest, organizationId, module.getId());
    }

    private String getAccessToken(Vcs vcs, String repository_source) {
        String token = vcs.getAttributes().getAccessToken();
        if (token == null && vcs.getAttributes().getConnectionType().equals("STANDALONE")) {
            log.info("The VCS connection is on a standalone app, getting the GitHub App token");
            GitHubAppToken gitHubAppToken = getGitHubAppTokenInformation(vcs.getAttributes().getClientId(), repository_source);
            if (gitHubAppToken == null || gitHubAppToken.getAttributes() == null) {
                log.warn("No GitHub App token found for VCS client id {} and repository source {}",
                        vcs.getAttributes().getClientId(), repository_source);
                return null;
            }
            token = gitHubAppToken.getAttributes().getToken();
        }
        return token;
    }

    private Vcs getVcsInformation(String organizationId, String vcsId) {
        return terrakubeClient.getVcsById(organizationId, vcsId).getData();
    }

    private Ssh getSshInformation(String organizationId, String sshId) {
        return terrakubeClient.getSshById(organizationId, sshId).getData();
    }
    
    private GitHubAppToken getGitHubAppTokenInformation(String vcsClientId, String repository_source) {
        URI uri = URI.create(repository_source);
        String owner = uri.getPath().split("/")[1];
        List<GitHubAppToken> gitHubAppTokens = terrakubeClient.getGitHubAppTokenByVcsIdAndOwner(owner, vcsClientId).getData();
        if (gitHubAppTokens.isEmpty())  return null;

        return gitHubAppTokens.get(0);
    }
}
