package io.terrakube.registry.plugin.storage.gcp;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.Blob;
import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GcpStorageServiceImplPresignedTest {

    @TempDir
    Path tempDir;

    // ---- Helper ----
    private GcpStorageServiceImpl buildService(Storage storage, GitService gitService, boolean redirectEnabled) {
        return GcpStorageServiceImpl.builder()
                .storage(storage)
                .gitService(gitService)
                .bucketName("test-bucket")
                .registryHostname("https://registry.terrakube.io")
                .presignedRedirectEnabled(redirectEnabled)
                .presignedUrlExpirySeconds(300)
                .build();
    }

    // ---- getPresignedDownloadUrl: flag OFF ----

    @Test
    void getPresignedDownloadUrl_whenDisabled_returnsEmpty() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);

        GcpStorageServiceImpl service = buildService(storage, gitService, false);
        Optional<URI> result = service.getPresignedDownloadUrl("org", "module", "gcp", "1.0.0");

        assertTrue(result.isEmpty(), "Expected Optional.empty() when presignedRedirectEnabled=false");
        verifyNoInteractions(storage);
    }

    // ---- getPresignedDownloadUrl: flag ON, V4-signed URL generated ----

    @Test
    void getPresignedDownloadUrl_whenEnabled_returnsSignedUri() throws Exception {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);

        URL signedUrl = new URL(
                "https://storage.googleapis.com/test-bucket/registry/org/module/gcp/1.0.0/module.zip"
                        + "?X-Goog-Signature=REDACTED&X-Goog-Algorithm=GOOG4-RSA-SHA256");

        when(storage.signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class),
                any(Storage.SignUrlOption.class))).thenReturn(signedUrl);

        GcpStorageServiceImpl service = buildService(storage, gitService, true);
        Optional<URI> result = service.getPresignedDownloadUrl("org", "module", "gcp", "1.0.0");

        assertTrue(result.isPresent(), "Expected a URI when presignedRedirectEnabled=true");
        String uriStr = result.get().toString();
        assertTrue(uriStr.contains("storage.googleapis.com"), "Should point to GCS");
        assertTrue(uriStr.contains("X-Goog-Signature"), "Should include GCS signature parameter");
    }

    // ---- getPresignedDownloadUrl: signUrl fails → StorageUnavailableException ----

    @Test
    void getPresignedDownloadUrl_whenSignUrlFails_throwsStorageUnavailable() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);

        when(storage.signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class),
                any(Storage.SignUrlOption.class))).thenThrow(new RuntimeException("signBlob IAM error"));

        GcpStorageServiceImpl service = buildService(storage, gitService, true);

        assertThrows(StorageUnavailableException.class,
                () -> service.getPresignedDownloadUrl("org", "module", "gcp", "1.0.0"));
    }

    // ---- downloadModule: success ----

    @Test
    void downloadModule_returnsBytes() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);
        Blob blob = mock(Blob.class);

        byte[] expected = "gcs-zip-content".getBytes();
        when(blob.getContent()).thenReturn(expected);
        when(storage.get(any(BlobId.class))).thenReturn(blob);

        GcpStorageServiceImpl service = buildService(storage, gitService, false);
        byte[] result = service.downloadModule("org", "module", "gcp", "1.0.0");

        assertArrayEquals(expected, result);
    }

    // ---- downloadModule: StorageException → StorageUnavailableException ----

    @Test
    void downloadModule_whenStorageExceptionThrown_throwsStorageUnavailable() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);

        when(storage.get(any(BlobId.class))).thenThrow(mock(StorageException.class));

        GcpStorageServiceImpl service = buildService(storage, gitService, false);

        assertThrows(StorageUnavailableException.class,
                () -> service.downloadModule("org", "module", "gcp", "1.0.0"));
    }

    // ---- searchModule: blob exists → no upload ----

    @Test
    void searchModule_whenBlobExists_skipsUpload() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);
        Blob blob = mock(Blob.class);

        when(storage.get(any(BlobId.class))).thenReturn(blob);

        GcpStorageServiceImpl service = buildService(storage, gitService, false);
        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = service.searchModule("org", "module", "gcp", download);

        assertEquals(
                "https://registry.terrakube.io/terraform/modules/v1/download/org/module/gcp/1.0.0/module.zip",
                result);
        verify(storage, never()).create(any(BlobInfo.class), any(byte[].class));
        verifyNoInteractions(gitService);
    }

    // ---- searchModule: blob does not exist → uploads ----

    @Test
    void searchModule_whenBlobNotExists_uploadsAndReturnsPath() throws IOException {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);

        when(storage.get(any(BlobId.class))).thenReturn(null);

        File gitCloneDir = tempDir.resolve("git-clone").toFile();
        assertTrue(gitCloneDir.mkdirs());
        FileUtils.writeStringToFile(new File(gitCloneDir, "main.tf"),
                "resource \"null_resource\" \"this\" {}", StandardCharsets.UTF_8);
        when(gitService.getCloneRepositoryByTag(any(ModuleVersionDownload.class))).thenReturn(gitCloneDir);

        GcpStorageServiceImpl service = buildService(storage, gitService, false);
        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = service.searchModule("org", "module", "gcp", download);

        assertEquals(
                "https://registry.terrakube.io/terraform/modules/v1/download/org/module/gcp/1.0.0/module.zip",
                result);
        verify(storage).create(any(BlobInfo.class), any(byte[].class));
    }

    // ---- searchModule: StorageException → StorageUnavailableException ----

    @Test
    void searchModule_whenStorageExceptionThrown_throwsStorageUnavailable() {
        Storage storage = mock(Storage.class);
        GitService gitService = mock(GitService.class);

        when(storage.get(any(BlobId.class))).thenThrow(mock(StorageException.class));

        GcpStorageServiceImpl service = buildService(storage, gitService, false);
        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");

        assertThrows(StorageUnavailableException.class,
                () -> service.searchModule("org", "module", "gcp", download));
        verifyNoInteractions(gitService);
    }
}
