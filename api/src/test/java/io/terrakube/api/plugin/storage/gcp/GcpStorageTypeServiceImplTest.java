package io.terrakube.api.plugin.storage.gcp;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GcpStorageTypeServiceImplTest {

    @Mock
    private Storage storage;
    @Mock
    private Blob blob;

    private GcpStorageTypeServiceImpl gcpStorageTypeService;
    private final String bucketName = "test-bucket";

    @BeforeEach
    void setUp() {
        gcpStorageTypeService = GcpStorageTypeServiceImpl.builder()
                .storage(storage)
                .bucketName(bucketName)
                .storageRetryMetrics(new io.terrakube.api.plugin.storage.StorageRetryMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .build();
    }

    @Test
    void testGetStepOutput() {
        byte[] expectedData = "output data".getBytes();
        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(blob.getContent()).thenReturn(expectedData);

        byte[] result = gcpStorageTypeService.getStepOutput("org1", "job1", "step1");

        assertArrayEquals(expectedData, result);
        verify(storage).get(BlobId.of(bucketName, "tfoutput/org1/job1/step1.tfoutput"));
    }

    @Test
    void testUploadTerraformStateJson() {
        gcpStorageTypeService.uploadTerraformStateJson("org1", "ws1", "{}", "hist1");

        verify(storage).create(any(BlobInfo.class), any(byte[].class));
    }

    @Test
    void testUploadStateUpdate() throws IOException {
        when(storage.get(BlobId.of(bucketName, "tfstate/org1/ws1/terraform.tfstate/default.tfstate"))).thenReturn(blob);
        com.google.cloud.WriteChannel channel = mock(com.google.cloud.WriteChannel.class);
        when(blob.writer()).thenReturn(channel);

        gcpStorageTypeService.uploadState("org1", "ws1", "state", "hist1");

        verify(channel).write(any());
        verify(channel).close();
        verify(storage).create(any(BlobInfo.class), any(byte[].class)); // raw history
    }

    @Test
    void testSaveContextNew() {
        when(storage.get(any(BlobId.class))).thenReturn(null);

        gcpStorageTypeService.saveContext(123, "{}");

        verify(storage).create(any(BlobInfo.class), any(byte[].class));
    }

    @Test
    void testGetContext() {
        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(blob.getContent()).thenReturn("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        String result = gcpStorageTypeService.getContext(123);

        assertEquals("{\"a\":1}", result);
    }

    @Test
    void getContextReturnsEmptyJsonOnNotFound() {
        when(storage.get(any(BlobId.class))).thenReturn(null);

        String result = gcpStorageTypeService.getContext(123);

        assertEquals("{}", result);
    }

    @Test
    void readThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        when(storage.get(any(BlobId.class))).thenThrow(new StorageException(503, "Service unavailable"));

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> gcpStorageTypeService.getContext(123));

        verify(storage, times(3)).get(any(BlobId.class));
    }

    @Test
    void writeThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        when(storage.get(any(BlobId.class))).thenReturn(null);
        when(storage.create(any(BlobInfo.class), any(byte[].class))).thenThrow(new StorageException(503, "Service unavailable"));

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> gcpStorageTypeService.saveContext(123, "{}"));

        verify(storage, times(3)).create(any(BlobInfo.class), any(byte[].class));
    }

    @Test
    void testCreateContentFile() throws IOException {
        when(storage.get(any(BlobId.class))).thenReturn(null);
        InputStream is = new ByteArrayInputStream("data".getBytes());

        gcpStorageTypeService.createContentFile("id1", is);

        verify(storage).create(any(BlobInfo.class), any(byte[].class));
    }

    @Test
    void testDeleteModuleStorage() {
        Page<Blob> page = mock(Page.class);
        when(page.iterateAll()).thenReturn(Collections.singletonList(blob));
        when(blob.getName()).thenReturn("file1");
        when(blob.getBlobId()).thenReturn(BlobId.of(bucketName, "file1"));
        
        when(storage.list(eq(bucketName), any(), any())).thenReturn(page);

        gcpStorageTypeService.deleteModuleStorage("org", "mod", "prov");

        verify(storage).delete(BlobId.of(bucketName, "file1"));
    }

    @Test
    void testMigrateToOrganization() {
        Page<Blob> page = mock(Page.class);
        when(page.iterateAll()).thenReturn(Collections.singletonList(blob));
        when(blob.getName()).thenReturn("tfoutput/org1/ws1/file1");
        when(blob.getBlobId()).thenReturn(BlobId.of(bucketName, "tfoutput/org1/ws1/file1"));

        when(storage.list(eq(bucketName), any(), any())).thenReturn(page);
        
        CopyWriter copyWriter = mock(CopyWriter.class);
        when(storage.copy(any(Storage.CopyRequest.class))).thenReturn(copyWriter);

        boolean result = gcpStorageTypeService.migrateToOrganization("org1", "ws1", "org2");

        assertTrue(result);
        verify(storage, atLeastOnce()).copy(any(Storage.CopyRequest.class));
    }

    @Test
    void testPolicyEvaluationLifecycle() {
        String uri = "policy-evaluations/123/violations.json";
        String json = "{\"violations\":[]}";

        gcpStorageTypeService.uploadPolicyEvaluation(uri, json);
        verify(storage).create(any(BlobInfo.class), eq(json.getBytes(StandardCharsets.UTF_8)));

        Blob blob = mock(Blob.class);
        when(storage.get(BlobId.of(bucketName, uri))).thenReturn(blob);
        when(blob.getContent()).thenReturn(json.getBytes(StandardCharsets.UTF_8));

        String retrieved = gcpStorageTypeService.getPolicyEvaluation(uri);
        assertEquals(json, retrieved);

        gcpStorageTypeService.deletePolicyEvaluation(uri);
        verify(storage).delete(BlobId.of(bucketName, uri));
    }

    @Test
    void testSpooledContextPatchLifecycle() {
        String orgId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String phase = "plan";
        String key = "context-spool/org1/job1/step1-plan.json.gz";
        byte[] data = "dummy-gzipped-bytes".getBytes(StandardCharsets.UTF_8);

        // 1. Get
        Blob blobMock = mock(Blob.class);
        when(storage.get(BlobId.of(bucketName, key))).thenReturn(blobMock);
        when(blobMock.getContent()).thenReturn(data);

        byte[] retrieved = gcpStorageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase);
        assertArrayEquals(data, retrieved);

        // 2. Has
        when(blobMock.exists()).thenReturn(true);
        assertTrue(gcpStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));

        // 3. Delete
        gcpStorageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
        verify(storage).delete(BlobId.of(bucketName, key));

        // 4. Missing key returns null / false
        when(storage.get(BlobId.of(bucketName, key))).thenReturn(null);
        assertFalse(gcpStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));
        assertNull(gcpStorageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase));
    }

    // ----- plan artifacts (plan-artifacts/<organization>/<workspace>/<job>/...)

    @Test
    void deletingAJobsArtifactsRemovesOnlyThatJobsPrefix() {
        Page<Blob> page = mock(Page.class);
        when(page.iterateAll()).thenReturn(Collections.singletonList(blob));
        when(blob.getName()).thenReturn("plan-artifacts/org1/ws1/12/step-a/plan-artifacts.tar.gz");
        when(blob.getBlobId()).thenReturn(BlobId.of(bucketName, "plan-artifacts/org1/ws1/12/step-a/plan-artifacts.tar.gz"));
        when(storage.list(eq(bucketName), any(), any())).thenReturn(page);

        gcpStorageTypeService.deleteJobArtifacts("org1", "ws1", 12);

        verify(storage).delete(BlobId.of(bucketName, "plan-artifacts/org1/ws1/12/step-a/plan-artifacts.tar.gz"));
    }

    @Test
    void deletingAWorkspaceAlsoRemovesItsArtifacts() {
        Page<Blob> empty = mock(Page.class);
        when(empty.iterateAll()).thenReturn(Collections.emptyList());
        when(storage.list(eq(bucketName), any(), any())).thenReturn(empty);

        gcpStorageTypeService.deleteWorkspaceStateData("org1", "ws1");

        // state, then plan artifacts
        verify(storage, times(2)).list(eq(bucketName), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsTheOutputPartsOfAStepWithTheirSizes() {
        Blob first = mock(Blob.class);
        Blob second = mock(Blob.class);
        when(first.getName()).thenReturn("tfoutput/org1/job1/step1.parts/000001.part");
        when(first.getSize()).thenReturn(5L);
        when(second.getName()).thenReturn("tfoutput/org1/job1/step1.parts/000002.part");
        when(second.getSize()).thenReturn(7L);
        com.google.api.gax.paging.Page<Blob> page = mock(com.google.api.gax.paging.Page.class);
        when(page.iterateAll()).thenReturn(java.util.List.of(first, second));
        when(storage.list(eq(bucketName), any(Storage.BlobListOption[].class))).thenReturn(page);

        var parts = gcpStorageTypeService.listStepOutputParts("org1", "job1", "step1");

        assertEquals(java.util.List.of(new io.terrakube.api.plugin.storage.model.StepOutputPart("000001.part", 5),
                new io.terrakube.api.plugin.storage.model.StepOutputPart("000002.part", 7)), parts);
    }

    @Test
    void opensARangeOfOneOutputPart() throws IOException {
        when(storage.get(BlobId.of(bucketName, "tfoutput/org1/job1/step1.parts/000002.part"))).thenReturn(blob);
        com.google.cloud.ReadChannel reader = mock(com.google.cloud.ReadChannel.class);
        when(blob.reader()).thenReturn(reader);

        try (java.io.InputStream in = gcpStorageTypeService.openStepOutputPart("org1", "job1", "step1", "000002.part", 3, 4)) {
            assertNotNull(in);
        }

        verify(reader).seek(3L);
        verify(reader).limit(7L);
    }

    @Test
    void anOutputPartNameThatIsNotGeneratedIsRefusedBeforeStorageIsAsked() {
        assertThrows(IOException.class, () -> gcpStorageTypeService.openStepOutputPart("org1", "job1", "step1", "../../state/terraform.tfstate", 0, 4));

        verify(storage, never()).get(any(BlobId.class));
    }

    @Test
    void aMissingPartIsAnError() {
        when(storage.get(any(BlobId.class))).thenReturn(null);

        assertThrows(IOException.class, () -> gcpStorageTypeService.openStepOutputPart("org1", "job1", "step1", "000002.part", 0, 4));
    }

    @Test
    void testCurrentTerraformStateLifecycle() {
        byte[] stateData = "{\"version\":4}".getBytes(StandardCharsets.UTF_8);
        String key = "tfstate/org1/ws1/terraform.tfstate/default.tfstate";

        when(storage.get(BlobId.of(bucketName, key))).thenReturn(null);
        gcpStorageTypeService.saveCurrentTerraformState("org1", "ws1", stateData);
        verify(storage).create(argThat((BlobInfo info) ->
                info.getBlobId().equals(BlobId.of(bucketName, key))
                        && "application/json".equals(info.getContentType())
        ), eq(stateData));

        when(storage.get(BlobId.of(bucketName, key))).thenReturn(blob);
        when(blob.getContent()).thenReturn(stateData);
        assertArrayEquals(stateData, gcpStorageTypeService.getCurrentTerraformState("org1", "ws1"));

        gcpStorageTypeService.deleteCurrentTerraformState("org1", "ws1");
        verify(storage).delete(BlobId.of(bucketName, key));
    }
}
