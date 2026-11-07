package io.terrakube.registry.plugin.storage;

import io.terrakube.registry.service.git.ModuleVersionDownload;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

public interface StorageService {

    String searchModule(String organizationName, String moduleName, String providerName, ModuleVersionDownload download);

    byte[] downloadModule(String organizationName, String moduleName, String providerName, String moduleVersion);

    /**
     * Returns a short-lived, directly-downloadable URL for the module ZIP when the storage
     * backend supports redirecting clients instead of proxying bytes through the registry pod.
     * Empty by default; S3, Azure Blob and GCS support this when their presignedRedirectEnabled
     * flag is on.
     */
    default Optional<URI> getPresignedDownloadUrl(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        return Optional.empty();
    }

    /** Reads any object by its key, for example {@code mirror/...}. Empty when it does not exist. */
    Optional<InputStream> readObject(String key);

    boolean objectExists(String key);

    /** Stores the file under the key, replacing any existing object. */
    void writeObject(String key, Path file, String contentType);

    /** Short-lived direct URL for the object, when the backend's presignedRedirectEnabled flag is on. */
    default Optional<URI> presignObject(String key) {
        return Optional.empty();
    }
}
