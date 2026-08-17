package io.terrakube.storage.plugin.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import io.terrakube.storage.plugin.core.GitService;
import io.terrakube.storage.plugin.core.ModuleVersionDownload;
import io.terrakube.storage.plugin.core.StorageService;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.IOException;

@Slf4j
@Builder
public class AzureStorageServiceImpl implements StorageService {

    private static final String CONTAINER_NAME = "registry";
    private static final String BUCKET_DOWNLOAD_MODULE_LOCATION = "%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip";

    @NonNull
    BlobServiceClient blobServiceClient;

    @NonNull
    GitService gitService;

    @NonNull
    String registryHostname;

    @Override
    public String searchModule(String organizationName, String moduleName, String providerName,
            ModuleVersionDownload download) {
        String moduleVersion = download.version();

        BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);

        log.info("blobContainerClient.exists {}", blobContainerClient.exists());
        if (!blobContainerClient.exists()) {
            blobContainerClient.create();
        }
        String blobName = String.format("%s/%s/%s/%s/module.zip", organizationName, moduleName, providerName,
                moduleVersion);
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

        return String.format(BUCKET_DOWNLOAD_MODULE_LOCATION, registryHostname, organizationName, moduleName,
                providerName, moduleVersion);
    }

    @Override
    public byte[] downloadModule(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);
        log.info("Searching: /registry/{}/{}/{}/{}/module.zip", organizationName, moduleName, providerName,
                moduleVersion);
        return containerClient.getBlobClient(
                String.format("%s/%s/%s/%s/module.zip", organizationName, moduleName, providerName, moduleVersion))
                .downloadContent().toBytes();
    }
}
