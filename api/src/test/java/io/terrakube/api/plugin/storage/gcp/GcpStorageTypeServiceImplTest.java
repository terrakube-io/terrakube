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
        when(storage.get(any(BlobId.class))).thenReturn(blob);
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
}
