package io.terrakube.registry.plugin.storage.gcp;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import io.terrakube.registry.service.git.GitService;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GcpStorageServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSearchModuleAndUploadIfNotExist() throws IOException {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);
        String bucketName = "test-bucket";
        String registryHostname = "https://registry.terrakube.io";

        GcpStorageServiceImpl gcpStorageService = GcpStorageServiceImpl.builder()
                .storage(storage)
                .bucketName(bucketName)
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        when(storage.get(any(BlobId.class))).thenReturn(null);

        // Mock gitService
        File gitCloneDir = tempDir.resolve("git-clone").toFile();
        assertTrue(gitCloneDir.mkdirs());
        File dummyFile = new File(gitCloneDir, "main.tf");
        FileUtils.writeStringToFile(dummyFile, "resource \"null_resource\" \"this\" {}", StandardCharsets.UTF_8);

        when(gitService.getCloneRepositoryByTag(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(gitCloneDir);

        String result = gcpStorageService.searchModule("org", "module", "gcp", "1.0.0", 
                "source", "vcsType", "vcsConn", "token", "tag", "folder");

        assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/gcp/1.0.0/module.zip", result);
        verify(storage).create(any(BlobInfo.class), any(byte[].class));
    }

    @Test
    void shouldSearchModuleAndReturnUrlIfExist() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);
        String bucketName = "test-bucket";
        String registryHostname = "https://registry.terrakube.io";

        GcpStorageServiceImpl gcpStorageService = GcpStorageServiceImpl.builder()
                .storage(storage)
                .bucketName(bucketName)
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        when(storage.get(any(BlobId.class))).thenReturn(mock(Blob.class));

        String result = gcpStorageService.searchModule("org", "module", "gcp", "1.0.0", 
                "source", "vcsType", "vcsConn", "token", "tag", "folder");

        assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/gcp/1.0.0/module.zip", result);
        verify(storage, never()).create(any(BlobInfo.class), any(byte[].class));
        verifyNoInteractions(gitService);
    }

    @Test
    void shouldDownloadModule() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);
        String bucketName = "test-bucket";

        GcpStorageServiceImpl gcpStorageService = GcpStorageServiceImpl.builder()
                .storage(storage)
                .bucketName(bucketName)
                .gitService(gitService)
                .registryHostname("host")
                .build();

        byte[] expectedData = "test-data".getBytes();
        Blob blob = mock(Blob.class);
        when(blob.getContent()).thenReturn(expectedData);
        when(storage.get(any(BlobId.class))).thenReturn(blob);

        byte[] result = gcpStorageService.downloadModule("org", "module", "gcp", "1.0.0");

        assertArrayEquals(expectedData, result);
    }
}
