package io.terrakube.storage.plugin.core;

public interface StorageService {

    String searchModule(String organizationName, String moduleName, String providerName, ModuleVersionDownload download);

    byte[] downloadModule(String organizationName, String moduleName, String providerName, String moduleVersion);
}
