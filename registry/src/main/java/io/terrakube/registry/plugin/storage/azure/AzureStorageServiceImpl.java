package io.terrakube.registry.plugin.storage.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
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
import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@Builder
public class AzureStorageServiceImpl implements StorageService {

    private static final String CONTAINER_NAME = "registry";
    private static final String BUCKET_DOWNLOAD_MODULE_LOCATION = "%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip";
    private static final String BLOB_ZIP_MODULE_LOCATION = "%s/%s/%s/%s/module.zip";

    @NonNull
    BlobServiceClient blobServiceClient;

    @NonNull
    GitService gitService;

    @NonNull
    String registryHostname;

    // Not @NonNull: only required when presignedRedirectEnabled is true. The SAS token is
    // generated from the blobServiceClient's shared-key credential (connection string path).
    // If Azure AD / Managed Identity is ever used instead, delegation via UserDelegationKey
    // would be required — see the module-download resilience design.
    @Builder.Default
    private int presignedUrlExpirySeconds = 300;

    @Builder.Default
    private boolean presignedRedirectEnabled = false;

    @Override
    public String searchModule(String organizationName, String moduleName, String providerName,
            ModuleVersionDownload download) {
        String moduleVersion = download.version();
        String blobName = String.format(BLOB_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);

        try {
            BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);

            log.info("blobContainerClient.exists {}", blobContainerClient.exists());
            if (!blobContainerClient.exists()) {
                blobContainerClient.create();
            }
            log.info("blobName: {}", blobName);
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);

            if (!blobClient.exists()) {
                File gitCloneDirectory = gitService.getCloneRepositoryByTag(download);
                File moduleZip = new File(gitCloneDirectory.getAbsolutePath() + ".zip");
                ZipUtil.pack(gitCloneDirectory, moduleZip);
                blobClient.uploadFromFile(moduleZip.getAbsolutePath());

                try {
                    FileUtils.cleanDirectory(gitCloneDirectory);
                    if (FileUtils.deleteQuietly(moduleZip))
                        log.info("Successfully delete folder");
                } catch (IOException e) {
                    log.error(e.getMessage());
                }
            }
        } catch (BlobStorageException e) {
            log.error("Azure Blob operation failed for blob {} in container {}: {}", blobName, CONTAINER_NAME,
                    e.getMessage());
            throw new StorageUnavailableException(
                    "Azure Blob operation failed while resolving module path for blob " + blobName, e);
        }

        return String.format(BUCKET_DOWNLOAD_MODULE_LOCATION, registryHostname, organizationName, moduleName,
                providerName, moduleVersion);
    }

    /**
     * Generates a short-lived, read-only SAS URL for the module ZIP blob so the Terraform client
     * can download it directly from Azure Blob Storage instead of the bytes being proxied through
     * the registry pod.
     *
     * <p>The SAS URL is intentionally not cached alongside the module path (see
     * getModuleVersionPath's 10-minute cache) — its validity window is much shorter than the cache
     * TTL, so every ZIP request gets a fresh SAS token computed here.
     *
     * <p>Requires the {@link BlobServiceClient} to be built with a shared-key credential
     * (connection string). SAS generation will fail if the client was built with Azure AD /
     * Managed Identity credentials without a UserDelegationKey.
     */
    @Override
    public Optional<URI> getPresignedDownloadUrl(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        if (!presignedRedirectEnabled) {
            return Optional.empty();
        }

        String blobName = String.format(BLOB_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        try {
            BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);

            BlobSasPermission permissions = new BlobSasPermission().setReadPermission(true);
            // startTime is set 60 seconds in the past to tolerate clock skew between the registry
            // host and Azure Storage. Without it, a client whose clock is even slightly ahead of
            // the server's can receive AuthenticationFailed ("Signature not valid yet") for an
            // otherwise valid SAS token issued right now.
            BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(
                    OffsetDateTime.now().plusSeconds(presignedUrlExpirySeconds), permissions)
                    .setStartTime(OffsetDateTime.now().minusMinutes(1));

            String sasToken = blobClient.generateSas(sasValues);
            // Never log the SAS token itself — it carries the signature query string.
            log.info("Generated Azure SAS download URL for container {} blob {}", CONTAINER_NAME, blobName);
            return Optional.of(URI.create(blobClient.getBlobUrl() + "?" + sasToken));
        } catch (Exception e) {
            log.error("Failed to generate Azure SAS URL for blob {} in container {}: {}", blobName, CONTAINER_NAME,
                    e.getMessage());
            throw new StorageUnavailableException("Failed to generate Azure SAS URL for blob " + blobName, e);
        }
    }

    /**
     * Legacy byte-proxy path, retained for the presigned-redirect rollback / compatibility case
     * ({@code presignedRedirectEnabled=false}). An Azure Blob failure here must surface as an
     * error, never as an empty ZIP that Terraform would try to unpack.
     */
    @Override
    public byte[] downloadModule(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        String blobName = String.format(BLOB_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        try {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);
            log.info("Searching: {}", blobName);
            return containerClient.getBlobClient(blobName).downloadContent().toBytes();
        } catch (BlobStorageException e) {
            log.error("Azure Blob download failed for blob {} in container {}: {}", blobName, CONTAINER_NAME,
                    e.getMessage());
            throw new StorageUnavailableException("Failed to download module ZIP for blob " + blobName, e);
        }
    }
}
