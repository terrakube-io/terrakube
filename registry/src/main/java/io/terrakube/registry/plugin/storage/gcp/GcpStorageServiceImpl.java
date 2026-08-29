package io.terrakube.registry.plugin.storage.gcp;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Builder
public class GcpStorageServiceImpl implements StorageService {

    private static final String GCP_ZIP_MODULE_LOCATION = "registry/%s/%s/%s/%s/module.zip";
    private static final String GCP_DOWNLOAD_MODULE_LOCATION = "%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip";

    @NonNull
    private String registryHostname;

    @NonNull
    private String bucketName;

    @NonNull
    private Storage storage;

    @NonNull
    private GitService gitService;

    @Builder.Default
    private int presignedUrlExpirySeconds = 300;

    @Builder.Default
    private boolean presignedRedirectEnabled = false;

    @Override
    public String searchModule(String organizationName, String moduleName, String providerName,
            ModuleVersionDownload download) {
        String moduleVersion = download.version();
        String blobKey = String.format(GCP_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        log.info("Searching module: {}", blobKey);
        BlobId blobId = BlobId.of(bucketName, blobKey);
        log.info("Checking GCP Object exist {}", blobKey);

        try {
            if (storage.get(blobId) == null) {
                File gitCloneDirectory = gitService.getCloneRepositoryByTag(download);
                File moduleZip = new File(gitCloneDirectory.getAbsolutePath() + ".zip");
                ZipUtil.pack(gitCloneDirectory, moduleZip);

                BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
                storage.create(blobInfo, FileUtils.readFileToByteArray(moduleZip));

                log.info("File uploaded to bucket {} as {}", bucketName, blobKey);

                try {
                    FileUtils.cleanDirectory(gitCloneDirectory);
                    if (FileUtils.deleteQuietly(moduleZip))
                        log.info("Successfully delete folder for gcp module");
                } catch (IOException e) {
                    log.error(e.getMessage());
                }
            }
        } catch (StorageException e) {
            log.error("GCP Storage operation failed for key {} in bucket {}: {}", blobKey, bucketName,
                    e.getMessage());
            throw new StorageUnavailableException(
                    "GCP Storage operation failed while resolving module path for key " + blobKey, e);
        } catch (IOException e) {
            log.error("IO error while preparing module upload for key {} in bucket {}: {}", blobKey, bucketName,
                    e.getMessage());
            throw new StorageUnavailableException(
                    "IO error while preparing GCP module upload for key " + blobKey, e);
        }

        return String.format(GCP_DOWNLOAD_MODULE_LOCATION, registryHostname, organizationName, moduleName,
                providerName, moduleVersion);
    }

    /**
     * Generates a short-lived, V4-signed GCS URL for the module ZIP so the Terraform client can
     * download it directly from Google Cloud Storage instead of having the bytes proxied through
     * the registry pod.
     *
     * <p>The signed URL is intentionally not cached alongside the module path (see
     * getModuleVersionPath's 10-minute cache) — its validity window is much shorter than the cache
     * TTL, so every ZIP request gets a fresh signature computed here.
     *
     * <p>Requires the service account credentials to have the
     * {@code iam.serviceAccounts.signBlob} IAM permission. This is available automatically
     * when using a service-account JSON key via {@code GoogleCredentials.fromStream(...)}.
     */
    @Override
    public Optional<URI> getPresignedDownloadUrl(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        if (!presignedRedirectEnabled) {
            return Optional.empty();
        }

        String blobKey = String.format(GCP_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, blobKey)).build();
            URL signedUrl = storage.signUrl(
                    blobInfo,
                    presignedUrlExpirySeconds,
                    TimeUnit.SECONDS,
                    Storage.SignUrlOption.withV4Signature());
            // Never log the signed URL itself — it carries the signature query string.
            log.info("Generated GCS V4-signed download URL for bucket {} key {}", bucketName, blobKey);
            return Optional.of(signedUrl.toURI());
        } catch (Exception e) {
            log.error("Failed to generate GCS signed URL for bucket {} key {}: {}", bucketName, blobKey,
                    e.getMessage());
            throw new StorageUnavailableException("Failed to generate GCS signed URL for key " + blobKey, e);
        }
    }

    /**
     * Legacy byte-proxy path, retained for the presigned-redirect rollback / compatibility case
     * ({@code presignedRedirectEnabled=false}). A GCS failure here must surface as an error,
     * never as an empty ZIP that Terraform would try to unpack.
     */
    @Override
    public byte[] downloadModule(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        String blobKey = String.format(GCP_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        log.info("Searching: {}", blobKey);
        try {
            return storage.get(BlobId.of(bucketName, blobKey)).getContent();
        } catch (StorageException e) {
            log.error("GCP Storage download failed for key {} in bucket {}: {}", blobKey, bucketName, e.getMessage());
            throw new StorageUnavailableException("Failed to download module ZIP for key " + blobKey, e);
        }
    }
}
