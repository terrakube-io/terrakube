package io.terrakube.registry.plugin.storage.local;

import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@Builder
@Slf4j
public class LocalStorageServiceImpl implements StorageService {

    private static final String DOWNLOAD_MODULE_LOCATION = "%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip";
    private static final String MODULE_LOCATION_ZIP = "/.terraform-spring-boot/local/modules/%s/%s/%s/%s/module.zip";

    @NonNull
    GitService gitService;
    @NonNull
    String registryHostname;

    @Override
    public synchronized String searchModule(String organizationName, String moduleName, String providerName,
            ModuleVersionDownload download) {
        String moduleVersion = download.version();
        String moduleFilePath = String.format(MODULE_LOCATION_ZIP, organizationName, moduleName, providerName,
                moduleVersion);
        log.info("moduleZip: {}", moduleFilePath);
        File moduleFile = new File(FileUtils.getUserDirectoryPath().concat(moduleFilePath));

        try {
            FileUtils.forceMkdirParent(moduleFile);
            if (!moduleFile.exists()) {
                File gitCloneDirectory = gitService.getCloneRepositoryByTag(download);
                log.info("Git Clone Directory {}", gitCloneDirectory.getAbsolutePath());
                log.info("moduleZip {}", moduleFile.getAbsolutePath());
                ZipUtil.pack(gitCloneDirectory, moduleFile);
                FileUtils.cleanDirectory(gitCloneDirectory);
            }
        } catch (IOException ex) {
            log.error(ex.getLocalizedMessage());
        }

        return String.format(DOWNLOAD_MODULE_LOCATION, registryHostname, organizationName, moduleName, providerName,
                moduleVersion);
    }

    @Override
    public byte[] downloadModule(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        String pathModule = String.format(MODULE_LOCATION_ZIP, organizationName, moduleName, providerName, moduleVersion);
        File localOutputDirectory = new File(FileUtils.getUserDirectoryPath().concat(pathModule));
        try {
            return IOUtils.toByteArray(new FileInputStream(localOutputDirectory));
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
