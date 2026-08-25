package io.terrakube.registry.plugin.storage;

import io.terrakube.registry.service.git.ModuleVersionDownload;

import java.net.URI;
import java.util.Optional;

public interface StorageService {

    String searchModule(String organizationName, String moduleName, String providerName, ModuleVersionDownload download);

    byte[] downloadModule(String organizationName, String moduleName, String providerName, String moduleVersion);

    /**
     * Returns a short-lived, directly-downloadable URL for the module ZIP when the storage
     * backend supports redirecting clients instead of proxying bytes through the registry pod.
     * Empty by default; only AWS S3 storage currently supports this, and only when explicitly
     * enabled.
     */
    default Optional<URI> getPresignedDownloadUrl(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        return Optional.empty();
    }
}
