package io.terrakube.registry.plugin.storage.gcp;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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

        File moduleZip = tempDir.resolve("module.zip").toFile();
        java.nio.file.Files.writeString(moduleZip.toPath(), "zip");
        doAnswer(invocation -> {
            invocation.<GitService.ModuleZipHandler>getArgument(1).accept(moduleZip);
            return null;
        }).when(gitService).withModuleZip(any(ModuleVersionDownload.class), any());

        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = gcpStorageService.searchModule("org", "module", "gcp", download);

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

        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = gcpStorageService.searchModule("org", "module", "gcp", download);

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

    @Test
    void readWriteExistsObject() throws IOException {
        Storage storage = mock(Storage.class);
        GcpStorageServiceImpl gcp = GcpStorageServiceImpl.builder()
                .storage(storage).bucketName("test-bucket").gitService(mock(GitService.class)).registryHostname("host").build();
        String key = "mirror/registry.terraform.io/hashicorp/random/index.json";
        BlobId blobId = BlobId.of("test-bucket", key);

        when(storage.get(blobId)).thenReturn(null);
        assertTrue(gcp.readObject(key).isEmpty());
        assertFalse(gcp.objectExists(key));

        when(storage.get(blobId)).thenReturn(mock(Blob.class));
        assertTrue(gcp.objectExists(key));

        Path file = tempDir.resolve("index.json");
        java.nio.file.Files.writeString(file, "{}");
        gcp.writeObject(key, file, "application/json");
        verify(storage).createFrom(BlobInfo.newBuilder(blobId).setContentType("application/json").build(), file);
        assertTrue(gcp.presignObject(key).isEmpty());
    }
}
