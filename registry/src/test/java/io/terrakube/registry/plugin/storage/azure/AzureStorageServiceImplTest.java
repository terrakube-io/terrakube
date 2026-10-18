package io.terrakube.registry.plugin.storage.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AzureStorageServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSearchModuleAndUploadIfNotExist() throws IOException {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient blobContainerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);
        String registryHostname = "https://registry.terrakube.io";

        AzureStorageServiceImpl azureStorageService = AzureStorageServiceImpl.builder()
                .blobServiceClient(blobServiceClient)
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(blobContainerClient);
        when(blobContainerClient.exists()).thenReturn(true);
        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        File moduleZip = tempDir.resolve("module.zip").toFile();
        java.nio.file.Files.writeString(moduleZip.toPath(), "zip");
        doAnswer(invocation -> {
            invocation.<GitService.ModuleZipHandler>getArgument(1).accept(moduleZip);
            return null;
        }).when(gitService).withModuleZip(any(ModuleVersionDownload.class), any());

        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = azureStorageService.searchModule("org", "module", "azure", download);

        assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/azure/1.0.0/module.zip", result);
        verify(blobClient).uploadFromFile(anyString());
    }

    @Test
    void shouldSearchModuleAndReturnUrlIfExist() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient blobContainerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        GitService gitService = mock(GitService.class);

        AzureStorageServiceImpl azureStorageService = AzureStorageServiceImpl.builder()
                .blobServiceClient(blobServiceClient)
                .gitService(gitService)
                .registryHostname("https://registry.terrakube.io")
                .build();

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(blobContainerClient);
        when(blobContainerClient.exists()).thenReturn(true);
        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);

        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = azureStorageService.searchModule("org", "module", "azure", download);

        assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/azure/1.0.0/module.zip", result);
        verify(blobClient, never()).uploadFromFile(anyString());
        verifyNoInteractions(gitService);
    }

    @Test
    void shouldDownloadModule() {
        BlobServiceClient blobServiceClient = mock(BlobServiceClient.class);
        BlobContainerClient blobContainerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);

        AzureStorageServiceImpl azureStorageService = AzureStorageServiceImpl.builder()
                .blobServiceClient(blobServiceClient)
                .gitService(mock(GitService.class))
                .registryHostname("host")
                .build();

        byte[] expectedData = "test-data".getBytes();
        BinaryData binaryData = BinaryData.fromBytes(expectedData);

        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(blobContainerClient);
        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.downloadContent()).thenReturn(binaryData);

        byte[] result = azureStorageService.downloadModule("org", "module", "azure", "1.0.0");

        assertArrayEquals(expectedData, result);
    }
}
