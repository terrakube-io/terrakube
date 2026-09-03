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
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
                      version {
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

    @Cacheable(cacheNames = {CacheConfig.MODULE_VERSIONS_CACHE}, key = "#organizationName + '-' + #moduleName + '-' + #providerName")
    @Override
    public List<String> getAvailableVersions(String organizationName, String moduleName, String providerName) {
        String organizationId = commonSearchService.getOrganizationId(organizationName);
        log.info("Search Module versions {}/{} in Organization {} with OrgId {}", moduleName, providerName, organizationName, organizationId);

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
    @Cacheable(
            cacheNames = {CacheConfig.MODULE_VERSION_PATH_CACHE},
            key = "#organizationName + '-' + #moduleName + '-' + #providerName + '-' + #version",
            sync = true)
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
            accessToken = getAccessToken(organizationId, vcsInformation.getId(), moduleSource);
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

    static final int DOWNLOAD_COUNT_MAX_ATTEMPTS = 3;
    private static final long[] DOWNLOAD_COUNT_BACKOFF_MILLIS = {500, 2000};

    // Runs on the dedicated downloadCountExecutor (DownloadCountExecutorConfig) so this
    // best-effort bookkeeping call never delays the Terraform-facing metadata response
    // (ModuleWebServiceImpl.getModuleVersionPath already returns before this completes). Failures
    // here must never surface to the caller or affect the HTTP response already issued - this
    // counter is not used for authorization, billing, or publication correctness.
    @Async("downloadCountExecutor")
    @Override
    public void updateModuleDownloadCount(String organizationName, String moduleName, String providerName) {
        for (int attempt = 1; attempt <= DOWNLOAD_COUNT_MAX_ATTEMPTS; attempt++) {
            try {
                doUpdateModuleDownloadCount(organizationName, moduleName, providerName);
                return;
            } catch (Exception e) {
                if (attempt == DOWNLOAD_COUNT_MAX_ATTEMPTS) {
                    log.error("Giving up updating download count for {}/{}/{} after {} attempts: {}",
                            organizationName, moduleName, providerName, attempt, e.getMessage());
                    return;
                }
                log.warn("Attempt {} to update download count for {}/{}/{} failed, retrying: {}",
                        attempt, organizationName, moduleName, providerName, e.getMessage());
                sleepBackoff(DOWNLOAD_COUNT_BACKOFF_MILLIS[attempt - 1]);
            }
        }
    }

    private void doUpdateModuleDownloadCount(String organizationName, String moduleName, String providerName) {
        String organizationId = commonSearchService.getOrganizationId(organizationName);
        Module module = terrakubeClient.getModuleByNameAndProvider(organizationId, moduleName, providerName).getData()
                .get(0);
        ModuleRequest moduleRequest = new ModuleRequest();
        ModuleAttributes moduleAttributes = new ModuleAttributes();
        moduleAttributes.setDownloadQuantity(module.getAttributes().getDownloadQuantity() + 1);
        module.setAttributes(moduleAttributes);
        moduleRequest.setData(module);

        terrakubeClient.updateModule(moduleRequest, organizationId, module.getId());
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private String getAccessToken(String organizationId, String vcsId, String repository_source) {
        Vcs vcs = getVcsInformation(organizationId, vcsId);
        if (vcs == null)
            return null;
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
