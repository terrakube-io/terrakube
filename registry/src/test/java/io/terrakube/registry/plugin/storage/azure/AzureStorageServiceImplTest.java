package io.terrakube.registry.plugin.storage.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import io.terrakube.registry.service.git.GitService;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

        // Mock gitService
        File gitCloneDir = tempDir.resolve("git-clone").toFile();
        assertTrue(gitCloneDir.mkdirs());
        File dummyFile = new File(gitCloneDir, "main.tf");
        FileUtils.writeStringToFile(dummyFile, "resource \"null_resource\" \"this\" {}", StandardCharsets.UTF_8);

        when(gitService.getCloneRepositoryByTag(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(gitCloneDir);

        String result = azureStorageService.searchModule("org", "module", "azure", "1.0.0", 
                "source", "vcsType", "vcsConn", "token", "tag", "folder");

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

        String result = azureStorageService.searchModule("org", "module", "azure", "1.0.0", 
                "source", "vcsType", "vcsConn", "token", "tag", "folder");

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
