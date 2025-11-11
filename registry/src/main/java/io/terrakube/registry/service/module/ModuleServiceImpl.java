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
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.search.CommonSearchService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@AllArgsConstructor
@Slf4j
@Service
public class ModuleServiceImpl implements ModuleService {

    TerrakubeClient terrakubeClient;
    StorageService storageService;
    CommonSearchService commonSearchService;

    public static final String SEARCH_ORGANIZATION_MODULE_VERSION="{ \n" +
            "  organization(filter: \"name==%s\") {\n" +
            "    edges {\n" +
            "      node {\n" +
            "        id\n" +
            "        name\n" +
            "        module(filter: \"name==%s;provider==%s\") {\n" +
            "            edges{\n" +
            "                node{\n" +
            "                    id\n" +
            "                    name\n" +
            "                    provider\n" +
            "                    version {\n" +
            "                        edges{\n" +
            "                            node{\n" +
            "                                id\n" +
            "                                version\n" +
            "                            }\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    @Cacheable(cacheNames = {"getAvailableVersions"}, key = "#organizationName + '-' + #moduleName + '-' + #providerName")
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

    @Cacheable(cacheNames = {"getModuleVersionPath"}, key = "#organizationName + '-' + #moduleName + '-' + #providerName + '-' + #version")
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

        moduleVersionPath = storageService.searchModule(
                organizationName, moduleName, providerName, version, moduleSource, vcsType, vcsConnectionType,
                accessToken, tagPrefix, folder);

        log.info("Registry Path: {}", moduleVersionPath);
        return moduleVersionPath;
    }

    @Override
    public void updateModuleDownloadCount(String organizationName, String moduleName, String providerName) {
        log.info("Update module download count");
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

    private String getAccessToken(String organizationId, String vcsId, String repository_source) {
        Vcs vcs = getVcsInformation(organizationId, vcsId);
        if (vcs == null)
            return null;
        String token = vcs.getAttributes().getAccessToken();
        if (token == null && vcs.getAttributes().getConnectionType().equals("STANDALONE")) {
            log.info("The VCS connection is on a standalone app, getting the GitHub App token");
            GitHubAppToken gitHubAppToken = getGitHubAppTokenInformation(vcs.getAttributes().getClientId(), repository_source);
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
