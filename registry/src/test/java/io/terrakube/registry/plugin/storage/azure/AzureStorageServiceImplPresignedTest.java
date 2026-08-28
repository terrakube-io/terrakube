package io.terrakube.registry.plugin.storage.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobStorageException;
import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AzureStorageServiceImplPresignedTest {

    @TempDir
    Path tempDir;

    // ---- Helper: build a service with presigned redirect enabled ----
    private AzureStorageServiceImpl buildServiceWithRedirect(BlobServiceClient blobServiceClient,
            GitService gitService, boolean redirectEnabled) {
        return AzureStorageServiceImpl.builder()
                .blobServiceClient(blobServiceClient)
                .gitService(gitService)
                .registryHostname("https://registry.terrakube.io")
                .presignedRedirectEnabled(redirectEnabled)
                .presignedUrlExpirySeconds(300)
                .build();
    }

    // ---- getPresignedDownloadUrl: flag OFF ----

    @Test
    void getPresignedDownloadUrl_whenDisabled_returnsEmpty() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        GitService gitService = mock(GitService.class);

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, false);
        Optional<URI> result = service.getPresignedDownloadUrl("org", "module", "azure", "1.0.0");

        assertTrue(result.isEmpty(), "Expected Optional.empty() when presignedRedirectEnabled=false");
        verifyNoInteractions(blobServiceClient);
    }

    // ---- getPresignedDownloadUrl: flag ON, SAS token generated ----

    @Test
    void getPresignedDownloadUrl_whenEnabled_returnsSasUri() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.getBlobUrl())
                .thenReturn("https://myaccount.blob.core.windows.net/registry/org/module/azure/1.0.0/module.zip");
        when(blobClient.generateSas(any())).thenReturn(
                "sv=2021-06-08&se=2099-01-01T00%3A00%3A00Z&sr=b&sp=r&sig=REDACTED");

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, true);
        Optional<URI> result = service.getPresignedDownloadUrl("org", "module", "azure", "1.0.0");

        assertTrue(result.isPresent(), "Expected a URI when presignedRedirectEnabled=true");
        String uriStr = result.get().toString();
        assertTrue(uriStr.startsWith("https://myaccount.blob.core.windows.net/registry/"),
                "SAS URL should point to Azure Blob");
        assertTrue(uriStr.contains("sig="), "SAS URL should include signature parameter");
    }

    // ---- getPresignedDownloadUrl: SAS generation fails → StorageUnavailableException ----

    @Test
    void getPresignedDownloadUrl_whenSasGenerationFails_throwsStorageUnavailable() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.getBlobUrl())
                .thenReturn("https://myaccount.blob.core.windows.net/registry/org/module/azure/1.0.0/module.zip");
        when(blobClient.generateSas(any())).thenThrow(new RuntimeException("SAS signing failure"));

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, true);

        assertThrows(StorageUnavailableException.class,
                () -> service.getPresignedDownloadUrl("org", "module", "azure", "1.0.0"));
    }

    // ---- downloadModule: success ----

    @Test
    void downloadModule_returnsBytes() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);

        byte[] expected = "zip-content".getBytes();
        com.azure.core.util.BinaryData binaryData = com.azure.core.util.BinaryData.fromBytes(expected);

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.downloadContent()).thenReturn(binaryData);

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, false);
        byte[] result = service.downloadModule("org", "module", "azure", "1.0.0");

        assertArrayEquals(expected, result);
    }

    // ---- downloadModule: Azure SDK error → StorageUnavailableException ----

    @Test
    void downloadModule_whenBlobStorageExceptionThrown_throwsStorageUnavailable() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.downloadContent()).thenThrow(
                mock(BlobStorageException.class));

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, false);

        assertThrows(StorageUnavailableException.class,
                () -> service.downloadModule("org", "module", "azure", "1.0.0"));
    }

    // ---- searchModule: blob already exists, no upload ----

    @Test
    void searchModule_whenBlobExists_skipsUpload() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(containerClient);
        when(containerClient.exists()).thenReturn(true);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, false);
        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = service.searchModule("org", "module", "azure", download);

        assertEquals(
                "https://registry.terrakube.io/terraform/modules/v1/download/org/module/azure/1.0.0/module.zip",
                result);
        verify(blobClient, never()).uploadFromFile(anyString());
        verifyNoInteractions(gitService);
    }

    // ---- searchModule: blob does NOT exist, uploads ----

    @Test
    void searchModule_whenBlobNotExists_uploadsAndReturnsPath() throws IOException {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(containerClient);
        when(containerClient.exists()).thenReturn(true);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        File gitCloneDir = tempDir.resolve("git-clone").toFile();
        assertTrue(gitCloneDir.mkdirs());
        FileUtils.writeStringToFile(new File(gitCloneDir, "main.tf"),
                "resource \"null_resource\" \"this\" {}", StandardCharsets.UTF_8);
        when(gitService.getCloneRepositoryByTag(any(ModuleVersionDownload.class))).thenReturn(gitCloneDir);

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, false);
        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = service.searchModule("org", "module", "azure", download);

        assertEquals(
                "https://registry.terrakube.io/terraform/modules/v1/download/org/module/azure/1.0.0/module.zip",
                result);
        verify(blobClient).uploadFromFile(anyString());
    }

    // ---- searchModule: Azure SDK error → StorageUnavailableException ----

    @Test
    void searchModule_whenBlobStorageExceptionThrown_throwsStorageUnavailable() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        GitService gitService = mock(GitService.class);

        BlobStorageException blobStorageException = mock(BlobStorageException.class);
        when(blobServiceClient.getBlobContainerClient("registry")).thenThrow(blobStorageException);

        AzureStorageServiceImpl service = buildServiceWithRedirect(blobServiceClient, gitService, false);
        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");

        assertThrows(StorageUnavailableException.class,
                () -> service.searchModule("org", "module", "azure", download));
        verifyNoInteractions(gitService);
    }
}
