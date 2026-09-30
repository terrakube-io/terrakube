package io.terrakube.registry.service.module;

import java.util.List;

public interface ModuleService {

    List<String> getAvailableVersions(String organizationName, String moduleName, String providerName);

    /** True when the version was removed (or cannot be a version at all); unknown versions are not removed. */
    boolean isVersionRemoved(String organizationName, String moduleName, String providerName, String version);

    /** Drops this replica's cached version list, e.g. after it offered a version that turned out to be removed. */
    void evictAvailableVersions(String organizationName, String moduleName, String providerName);

    String getModuleVersionPath(String organizationName, String moduleName, String providerName, String version);

    void updateModuleDownloadCount(String organizationName, String moduleName, String providerName);
}
