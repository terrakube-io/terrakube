package io.terrakube.api.plugin.storage.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwsStorageTypeServiceImplTest {

    @Mock
    private S3Client s3Client;

    private AwsStorageTypeServiceImpl awsStorageTypeService;
    private final String bucketName = "test-bucket";

    @BeforeEach
    void setUp() {
        awsStorageTypeService = AwsStorageTypeServiceImpl.builder()
                .s3client(s3Client)
                .bucketName(bucketName)
                .build();
    }

    @Test
    void testGetStepOutput() {
        String orgId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        byte[] expectedData = "output data".getBytes();

        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(expectedData);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(responseBytes);

        byte[] result = awsStorageTypeService.getStepOutput(orgId, jobId, stepId);

        assertArrayEquals(expectedData, result);
        verify(s3Client).getObject(argThat((GetObjectRequest request) ->
                request.bucket().equals(bucketName) &&
                request.key().equals("tfoutput/org1/job1/step1.tfoutput")
        ), any(ResponseTransformer.class));
    }

    @Test
    void testGetTerraformPlan() {
        byte[] expectedData = "plan data".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(expectedData);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(responseBytes);

        byte[] result = awsStorageTypeService.getTerraformPlan("org1", "ws1", "job1", "step1");

        assertArrayEquals(expectedData, result);
        verify(s3Client).getObject(argThat((GetObjectRequest request) ->
                request.key().equals("tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan")
        ), any(ResponseTransformer.class));
    }

    @Test
    void testUploadTerraformStateJson() {
        String stateJson = "{\"status\":\"ok\"}";
        awsStorageTypeService.uploadTerraformStateJson("org1", "ws1", stateJson, "hist1");

        verify(s3Client).putObject(argThat((PutObjectRequest request) ->
                request.bucket().equals(bucketName) &&
                request.key().equals("tfstate/org1/ws1/state/hist1.json")
        ), any(RequestBody.class));
    }

    @Test
    void testUploadState() {
        String state = "state content";
        awsStorageTypeService.uploadState("org1", "ws1", state, "hist1");

        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3Client).putObject(argThat((PutObjectRequest request) ->
                request.key().equals("tfstate/org1/ws1/terraform.tfstate")
        ), any(RequestBody.class));
        verify(s3Client).putObject(argThat((PutObjectRequest request) ->
                request.key().equals("tfstate/org1/ws1/state/hist1.raw.json")
        ), any(RequestBody.class));
    }

    @Test
    void testSaveContext() {
        String context = "{\"key\":\"val\"}";
        awsStorageTypeService.saveContext(123, context);

        verify(s3Client).putObject(argThat((PutObjectRequest request) ->
                request.key().equals("tfoutput/context/123/context.json")
        ), any(RequestBody.class));
    }

    @Test
    void testGetContext() {
        byte[] contextData = "{\"key\":\"val\"}".getBytes(StandardCharsets.UTF_8);
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(contextData);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(responseBytes);

        String result = awsStorageTypeService.getContext(123);

        assertEquals("{\"key\":\"val\"}", result);
    }

    @Test
    void testGetContextEmpty() {
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenThrow(S3Exception.builder().message("Not found").build());

        String result = awsStorageTypeService.getContext(123);

        assertEquals("{}", result);
    }

    @Test
    void testCreateContentFile() throws IOException {
        InputStream is = new ByteArrayInputStream("content".getBytes());
        awsStorageTypeService.createContentFile("content1", is);

        verify(s3Client).putObject(argThat((PutObjectRequest request) ->
                request.key().equals("content/content1/terraformContent.tar.gz") &&
                request.contentType().equals("application/gzip")
        ), any(RequestBody.class));
    }

    @Test
    void testDeleteModuleStorage() {
        S3Object s3Object = S3Object.builder().key("registry/org/mod/prov/file1").build();
        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(Collections.singletonList(s3Object))
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        awsStorageTypeService.deleteModuleStorage("org", "mod", "prov");

        verify(s3Client).deleteObject(argThat((DeleteObjectRequest request) ->
                request.key().equals("registry/org/mod/prov/file1")
        ));
    }

    @Test
    void testMigrateToOrganization() {
        S3Object s3Object = S3Object.builder().key("tfstate/org1/ws1/file1").build();
        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(Collections.singletonList(s3Object))
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        boolean result = awsStorageTypeService.migrateToOrganization("org1", "ws1", "org2");

        assertTrue(result);
        verify(s3Client).copyObject(argThat((CopyObjectRequest request) ->
                request.copySource().equals(bucketName + "/tfstate/org1/ws1/file1") &&
                request.key().equals("tfstate/org2/ws1/file1")
        ));
    }
}
