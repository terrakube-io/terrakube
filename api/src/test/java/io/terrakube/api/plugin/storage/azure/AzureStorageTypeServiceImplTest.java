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
                .storageRetryMetrics(new io.terrakube.api.plugin.storage.StorageRetryMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
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
        when(containerClient.getBlobClient("context/123/context.json")).thenReturn(blobClient);

        azureStorageTypeService.saveContext(123, "{}");

        verify(blobClient).upload(any(BinaryData.class), eq(true));
    }

    @Test
    void testGetContext() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.downloadContent()).thenReturn(BinaryData.fromString("{\"a\":1}"));

        String result = azureStorageTypeService.getContext(123);

        assertEquals("{\"a\":1}", result);
    }

    @Test
    void getContextReturnsEmptyJsonOnNotFound() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        com.azure.storage.blob.models.BlobStorageException notFound = mock(com.azure.storage.blob.models.BlobStorageException.class);
        when(notFound.getStatusCode()).thenReturn(404);
        when(blobClient.downloadContent()).thenThrow(notFound);

        String result = azureStorageTypeService.getContext(123);

        assertEquals("{}", result);
    }

    @Test
    void readThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.downloadContent()).thenThrow(new RuntimeException("connection reset"));

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> azureStorageTypeService.getContext(123));

        verify(blobClient, times(3)).downloadContent();
    }

    @Test
    void writeThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        doThrow(new RuntimeException("connection reset")).when(blobClient).upload(any(BinaryData.class), eq(true));

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> azureStorageTypeService.saveContext(123, "{}"));

        verify(blobClient, times(3)).upload(any(BinaryData.class), eq(true));
    }

    @Test
    void testCreateContentFile() throws IOException {
        when(blobServiceClient.getBlobContainerClient("content")).thenReturn(containerClient);
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        InputStream is = new ByteArrayInputStream("data".getBytes());

        azureStorageTypeService.createContentFile("id1", is);

        verify(containerClient).createIfNotExists();
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

    @Test
    void testPolicyEvaluationLifecycle() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.exists()).thenReturn(true);
        String uri = "policy-evaluations/123/violations.json";
        String json = "{\"violations\":[]}";

        when(containerClient.getBlobClient(uri)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.downloadContent()).thenReturn(BinaryData.fromString(json));

        azureStorageTypeService.uploadPolicyEvaluation(uri, json);
        verify(blobClient).upload(any(BinaryData.class), eq(true));

        String retrieved = azureStorageTypeService.getPolicyEvaluation(uri);
        assertEquals(json, retrieved);

        azureStorageTypeService.deletePolicyEvaluation(uri);
        verify(blobClient).delete();
    }

    @Test
    void testSpooledContextPatchLifecycle() {
        String orgId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String phase = "plan";
        String blobName = "context-spool/org1/job1/step1-plan.json.gz";
        byte[] data = "dummy-gzipped-bytes".getBytes(StandardCharsets.UTF_8);

        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient(blobName)).thenReturn(blobClient);

        // 1. Get
        when(blobClient.downloadContent()).thenReturn(BinaryData.fromBytes(data));
        byte[] retrieved = azureStorageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase);
        assertArrayEquals(data, retrieved);

        // 2. Has
        when(blobClient.exists()).thenReturn(true);
        assertTrue(azureStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));

        // 3. Delete
        azureStorageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
        verify(blobClient).deleteIfExists();

        // 4. Missing key returns null / false
        when(blobClient.exists()).thenReturn(false);
        assertFalse(azureStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));
    }

    // ----- plan artifacts (same container as state, under plan-artifacts/)

    private void containerListsBlobs(String container, String... names) {
        when(blobServiceClient.getBlobContainerClient(container)).thenReturn(containerClient);
        com.azure.core.http.rest.PagedIterable<BlobItem> paged = mock(com.azure.core.http.rest.PagedIterable.class);
        java.util.List<BlobItem> items = new java.util.ArrayList<>();
        for (String name : names) {
            BlobItem item = mock(BlobItem.class);
            when(item.getName()).thenReturn(name);
            items.add(item);
            when(containerClient.getBlobClient(name)).thenReturn(blobClient);
        }
        when(paged.iterator()).thenReturn(items.iterator());
        when(containerClient.listBlobs(any(ListBlobsOptions.class), any())).thenReturn(paged);
    }

    @Test
    void deletingAJobsArtifactsRemovesOnlyThatJobsPrefixInTheStateContainer() {
        containerListsBlobs("tfstate", "plan-artifacts/org1/ws1/12/step-a/plan-artifacts.tar.gz");

        azureStorageTypeService.deleteJobArtifacts("org1", "ws1", 12);

        org.mockito.ArgumentCaptor<ListBlobsOptions> options = org.mockito.ArgumentCaptor.forClass(ListBlobsOptions.class);
        verify(containerClient).listBlobs(options.capture(), any());
        assertEquals("plan-artifacts/org1/ws1/12/", options.getValue().getPrefix());
        verify(blobClient).delete();
    }

    @Test
    void deletingAWorkspaceAlsoRemovesItsArtifacts() {
        containerListsBlobs("tfstate");

        azureStorageTypeService.deleteWorkspaceStateData("org1", "ws1");

        org.mockito.ArgumentCaptor<ListBlobsOptions> options = org.mockito.ArgumentCaptor.forClass(ListBlobsOptions.class);
        verify(containerClient, times(2)).listBlobs(options.capture(), any());
        assertEquals(java.util.List.of("org1/ws1", "plan-artifacts/org1/ws1/"),
                options.getAllValues().stream().map(ListBlobsOptions::getPrefix).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsTheOutputPartsOfAStepWithTheirSizes() {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        com.azure.core.http.rest.PagedIterable<com.azure.storage.blob.models.BlobItem> paged = mock(com.azure.core.http.rest.PagedIterable.class);
        java.util.List<com.azure.storage.blob.models.BlobItem> items = java.util.List.of(
                new com.azure.storage.blob.models.BlobItem().setName("org1/job1/step1.parts/000001.part")
                        .setProperties(new com.azure.storage.blob.models.BlobItemProperties().setContentLength(5L)),
                new com.azure.storage.blob.models.BlobItem().setName("org1/job1/step1.parts/000002.part")
                        .setProperties(new com.azure.storage.blob.models.BlobItemProperties().setContentLength(7L)));
        doAnswer(invocation -> {
            items.forEach(invocation.<java.util.function.Consumer<com.azure.storage.blob.models.BlobItem>>getArgument(0));
            return null;
        }).when(paged).forEach(any());
        when(containerClient.listBlobs(any(ListBlobsOptions.class), any())).thenReturn(paged);

        var parts = azureStorageTypeService.listStepOutputParts("org1", "job1", "step1");

        assertEquals(java.util.List.of(new io.terrakube.api.plugin.storage.model.StepOutputPart("000001.part", 5),
                new io.terrakube.api.plugin.storage.model.StepOutputPart("000002.part", 7)), parts);
        org.mockito.ArgumentCaptor<ListBlobsOptions> options = org.mockito.ArgumentCaptor.forClass(ListBlobsOptions.class);
        verify(containerClient).listBlobs(options.capture(), any());
        assertEquals("org1/job1/step1.parts/", options.getValue().getPrefix());
    }

    @Test
    void anOutputPartNameThatIsNotGeneratedIsRefusedBeforeStorageIsAsked() {
        assertThrows(java.io.IOException.class, () -> azureStorageTypeService.openStepOutputPart("org1", "job1", "step1", "../../state/terraform.tfstate", 0, 4));

        verify(containerClient, never()).getBlobClient(any());
    }

    @Test
    void opensARangeOfOneOutputPart() throws java.io.IOException {
        when(blobServiceClient.getBlobContainerClient("tfoutput")).thenReturn(containerClient);
        when(containerClient.getBlobClient("org1/job1/step1.parts/000002.part")).thenReturn(blobClient);
        com.azure.storage.blob.specialized.BlobInputStream stream = mock(com.azure.storage.blob.specialized.BlobInputStream.class);
        org.mockito.ArgumentCaptor<com.azure.storage.blob.options.BlobInputStreamOptions> options =
                org.mockito.ArgumentCaptor.forClass(com.azure.storage.blob.options.BlobInputStreamOptions.class);
        when(blobClient.openInputStream(options.capture())).thenReturn(stream);

        assertSame(stream, azureStorageTypeService.openStepOutputPart("org1", "job1", "step1", "000002.part", 3, 4));

        assertEquals(3L, options.getValue().getRange().getOffset());
        assertEquals(4L, options.getValue().getRange().getCount());
    }
}
