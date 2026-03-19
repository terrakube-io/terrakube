package io.terrakube.api.plugin.storage.azure;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AzureStorageTypeServiceImplTest {

    @Mock
    private BlobServiceClient blobServiceClient;
    @Mock
    private BlobContainerClient containerClient;
    @Mock
    private BlobClient blobClient;

    private AzureStorageTypeServiceImpl azureStorageTypeService;

    @BeforeEach
    void setUp() {
        azureStorageTypeService = AzureStorageTypeServiceImpl.builder()
                .blobServiceClient(blobServiceClient)
                .build();
    }

    @Test
    void testGetStepOutput() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient("org1/job1/step1.tfoutput")).thenReturn(blobClient);
        byte[] expectedData = "output data".getBytes();
        when(blobClient.downloadContent()).thenReturn(BinaryData.fromBytes(expectedData));

        byte[] result = azureStorageTypeService.getStepOutput("org1", "job1", "step1");

        assertArrayEquals(expectedData, result);
    }

    @Test
    void testUploadTerraformStateJson() {
        when(blobServiceClient.getBlobContainerClient("tfstate")).thenReturn(containerClient);
        when(containerClient.getBlobClient("org1/ws1/state/hist1.json")).thenReturn(blobClient);

        azureStorageTypeService.uploadTerraformStateJson("org1", "ws1", "{}", "hist1");

        verify(blobClient).upload(any(BinaryData.class), eq(true));
    }

    @Test
    void testUploadState() {
        when(blobServiceClient.getBlobContainerClient("tfstate")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);

        azureStorageTypeService.uploadState("org1", "ws1", "state", "hist1");

        verify(blobClient, times(2)).upload(any(BinaryData.class), eq(true));
    }

    @Test
    void testSaveContext() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.exists()).thenReturn(true);
        when(containerClient.getBlobClient("context/123/context.json")).thenReturn(blobClient);

        azureStorageTypeService.saveContext(123, "{}");

        verify(blobClient).upload(any(BinaryData.class), eq(true));
    }

    @Test
    void testGetContext() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.downloadContent()).thenReturn(BinaryData.fromString("{\"a\":1}"));

        String result = azureStorageTypeService.getContext(123);

        assertEquals("{\"a\":1}", result);
    }

    @Test
    void testCreateContentFile() throws IOException {
        when(blobServiceClient.getBlobContainerClient("content")).thenReturn(containerClient);
        when(containerClient.exists()).thenReturn(false);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        InputStream is = new ByteArrayInputStream("data".getBytes());

        azureStorageTypeService.createContentFile("id1", is);

        verify(containerClient).create();
        verify(blobClient).upload(any(BinaryData.class), eq(true));
    }

    @Test
    void testDeleteModuleStorage() {
        when(blobServiceClient.getBlobContainerClient("registry")).thenReturn(containerClient);
        BlobItem blobItem = mock(BlobItem.class);
        when(blobItem.getName()).thenReturn("file1");
        
        com.azure.core.http.rest.PagedIterable<BlobItem> pagedIterable = mock(com.azure.core.http.rest.PagedIterable.class);
        when(pagedIterable.iterator()).thenReturn(Collections.singletonList(blobItem).iterator());
        when(containerClient.listBlobs(any(ListBlobsOptions.class), any())).thenReturn(pagedIterable);
        
        when(containerClient.getBlobClient("file1")).thenReturn(blobClient);

        azureStorageTypeService.deleteModuleStorage("org", "mod", "prov");

        verify(blobClient).delete();
    }

    @Test
    void testMigrateToOrganization() {
        when(blobServiceClient.getBlobContainerClient(anyString())).thenReturn(containerClient);
        BlobItem blobItem = mock(BlobItem.class);
        when(blobItem.getName()).thenReturn("org1/ws1/file1");
        
        com.azure.core.http.rest.PagedIterable<BlobItem> pagedIterable = mock(com.azure.core.http.rest.PagedIterable.class);
        // Both stream() and forEach are used by PagedIterable sometimes, but the implementation uses forEach
        doAnswer(invocation -> {
            java.util.function.Consumer<? super BlobItem> consumer = invocation.getArgument(0);
            consumer.accept(blobItem);
            return null;
        }).when(pagedIterable).forEach(any(java.util.function.Consumer.class));
        
        when(containerClient.listBlobs(any(ListBlobsOptions.class), any())).thenReturn(pagedIterable);
        
        BlobClient sourceBlobClient = mock(BlobClient.class);
        BlobClient targetBlobClient = mock(BlobClient.class);
        when(containerClient.getBlobClient("org1/ws1/file1")).thenReturn(sourceBlobClient);
        when(containerClient.getBlobClient("org2/ws1/file1")).thenReturn(targetBlobClient);
        when(sourceBlobClient.downloadContent()).thenReturn(BinaryData.fromString("data"));

        boolean result = azureStorageTypeService.migrateToOrganization("org1", "ws1", "org2");

        assertTrue(result);
        verify(targetBlobClient, atLeastOnce()).upload(any(BinaryData.class), eq(true));
    }
}
