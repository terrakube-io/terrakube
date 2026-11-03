package io.terrakube.registry.plugin.storage.local;

import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

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
                log.info("moduleZip {}", moduleFile.getAbsolutePath());
                gitService.withModuleZip(download, moduleZip -> Files.move(moduleZip.toPath(), moduleFile.toPath()));
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
        try (FileInputStream in = new FileInputStream(localOutputDirectory)) {
            return IOUtils.toByteArray(in);
        } catch (IOException e) {
            // Same contract as the cloud backends: a failed read is an error, never an empty archive.
            throw new StorageUnavailableException("Failed to read module ZIP " + localOutputDirectory, e);
        }
    }

    @Override
    public Optional<InputStream> readObject(String key) {
        Path file = resolveKey(key);
        try {
            return Optional.of(Files.newInputStream(file));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new StorageUnavailableException("Failed to read " + file, e);
        }
    }

    @Override
    public boolean objectExists(String key) {
        return Files.isRegularFile(resolveKey(key));
    }

    @Override
    public void writeObject(String key, Path file, String contentType) {
        Path target = resolveKey(key);
        try {
            Files.createDirectories(target.getParent());
            // Copy next to the target, then rename, so readers never see a half-written file.
            Path temp = Files.createTempFile(target.getParent(), ".upload", null);
            try {
                Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new StorageUnavailableException("Failed to write " + target, e);
        }
    }

    private static Path resolveKey(String key) {
        Path base = Paths.get(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot", "local").normalize();
        Path resolved = base.resolve(key).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("Storage key escapes the local storage directory: " + key);
        }
        return resolved;
    }
}
