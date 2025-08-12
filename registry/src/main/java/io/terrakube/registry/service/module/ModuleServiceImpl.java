package io.terrakube.registry.service.module;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.module.Module;
import io.terrakube.client.model.organization.module.ModuleAttributes;
import io.terrakube.client.model.organization.module.ModuleRequest;
import io.terrakube.client.model.organization.module.version.ModuleVersion;
import io.terrakube.client.model.organization.ssh.Ssh;
import io.terrakube.client.model.organization.vcs.Vcs;
import io.terrakube.client.model.organization.vcs.github_app_token.GitHubAppToken;
import io.terrakube.registry.plugin.storage.StorageService;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
@Service
public class ModuleServiceImpl implements ModuleService {

    TerrakubeClient terrakubeClient;
    StorageService storageService;

    @Override
    public List<String> getAvailableVersions(String organizationName, String moduleName, String providerName) {
        List<String> definitionVersions = new ArrayList<>();
        String organizationId = terrakubeClient.getOrganizationByName(organizationName).getData().get(0).getId();
        log.info("Search Organization: {} {}", organizationName, organizationId);

        log.info("Search Module {}/{} in Organization {}", moduleName, providerName, organizationName);
        Module module = terrakubeClient.getModuleByNameAndProvider(organizationId, moduleName, providerName).getData()
                .get(0);
        // This is only required as a intermediate solution for the modules created before this version to create a scheduled task to fetch the versions
        // All modules created after this version will have the task to fetch the versions. Check the ModuleManageHook.java in API for more details
        List<Resource> versionData = module.getRelationships().getVersion().getData();
        if (versionData == null || versionData.isEmpty()) {
            log.info("No versions found for module: {} in organization: {}, kicking off a update call to the module for it to refresh its versions", moduleName, organizationName);
            updateModuleDownloadCount(organizationId, module);
            return definitionVersions;
        }

        List<ModuleVersion> versionList = terrakubeClient.getAllVersionsByOrganizationIdAndModuleId(organizationId, module.getId()).getData();
        log.debug("Version list: {}", (Object) versionList.stream()
                .map(version -> version.getAttributes().getVersion())
                .toArray(String[]::new));

        for (ModuleVersion version : versionList) {
            definitionVersions.add(version.getAttributes().getVersion());
        }
        return definitionVersions;
    }

    @Override
    public String getModuleVersionPath(String organizationName, String moduleName, String providerName, String version,
            boolean countDownload) {
        String moduleVersionPath = "";

        String organizationId = terrakubeClient.getOrganizationByName(organizationName).getData().get(0).getId();
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

        if (countDownload)
            updateModuleDownloadCount(organizationId, module);

        log.info("Registry Path: {}", moduleVersionPath);
        return moduleVersionPath;
    }

    private void updateModuleDownloadCount(String organizationId, Module module) {
        log.info("Update module download count");
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
        if (gitHubAppTokens.size() == 0)  return null;

        return gitHubAppTokens.get(0);
    }
}
