package io.terrakube.registry.plugin.storage;

import io.terrakube.registry.service.git.ModuleVersionDownload;

public interface StorageService {

    String searchModule(String organizationName, String moduleName, String providerName, ModuleVersionDownload download);

    byte[] downloadModule(String organizationName, String moduleName, String providerName, String moduleVersion);
}
