package io.terrakube.api.plugin.storage.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
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
import static org.mockito.ArgumentMatchers.argThat;
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
                .storageRetryMetrics(new io.terrakube.api.plugin.storage.StorageRetryMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
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
    void getStepOutputStreamReturnsContentAndLength() {
        GetObjectResponse response = GetObjectResponse.builder().contentLength(11L).build();
        ResponseInputStream<GetObjectResponse> ris =
                new ResponseInputStream<>(response, new ByteArrayInputStream("hello world".getBytes()));
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(ris);

        StepOutputStream result = awsStorageTypeService.getStepOutputStream("org1", "job1", "step1", null);

        assertTrue(result.isExists());
        assertFalse(result.isPartial());
        assertEquals(11L, result.getContentLength());
    }

    @Test
    void getStepOutputStreamReturnsMissingOnNoSuchKey() {
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        StepOutputStream result = awsStorageTypeService.getStepOutputStream("org1", "job1", "step1", null);

        assertFalse(result.isExists());
    }

    @Test
    void getStepOutputStreamPassesRangeToS3AndReportsContentRange() {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(100L).contentRange("bytes 900-999/1000").build();
        ResponseInputStream<GetObjectResponse> ris =
                new ResponseInputStream<>(response, new ByteArrayInputStream(new byte[100]));
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(ris);

        ByteRange range = ByteRange.parse("bytes=-100").orElseThrow();
        StepOutputStream result = awsStorageTypeService.getStepOutputStream("org1", "job1", "step1", range);

        assertTrue(result.isPartial());
        assertEquals("bytes 900-999/1000", result.getContentRange());
        assertEquals(1000L, result.getTotalLength());
        verify(s3Client).getObject(argThat((GetObjectRequest r) -> "bytes=-100".equals(r.range())),
                any(ResponseTransformer.class));
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
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenThrow(NoSuchKeyException.builder().build());

        String result = awsStorageTypeService.getContext(123);

        assertEquals("{}", result);
    }

    @Test
    void readThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(S3Exception.builder().message("Service unavailable").build());

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> awsStorageTypeService.getContext(123));

        verify(s3Client, times(3)).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    void writeThrowsStorageUnavailableAfterRetryingAGenuineFailure() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("Service unavailable").build());

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> awsStorageTypeService.saveContext(123, "{}"));

        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
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

    @Test
    void testPolicyEvaluationLifecycle() {
        String uri = "policy-evaluations/123/violations.json";
        String json = "{\"violations\":[]}";

        awsStorageTypeService.uploadPolicyEvaluation(uri, json);
        verify(s3Client).putObject(argThat((PutObjectRequest req) ->
                req.bucket().equals(bucketName) && req.key().equals(uri)), any(RequestBody.class));

        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(responseBytes);

        String retrieved = awsStorageTypeService.getPolicyEvaluation(uri);
        assertEquals(json, retrieved);

        awsStorageTypeService.deletePolicyEvaluation(uri);
        verify(s3Client).deleteObject(argThat((DeleteObjectRequest req) ->
                req.bucket().equals(bucketName) && req.key().equals(uri)));
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
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(data);
        when(s3Client.getObject(argThat((GetObjectRequest req) -> req != null && key.equals(req.key())), any(ResponseTransformer.class)))
                .thenReturn(responseBytes);

        byte[] retrieved = awsStorageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase);
        assertArrayEquals(data, retrieved);

        // 2. Has
        when(s3Client.headObject(argThat((HeadObjectRequest req) -> req != null && key.equals(req.key()))))
                .thenReturn(HeadObjectResponse.builder().build());
        assertTrue(awsStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));

        // 3. Delete
        awsStorageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
        verify(s3Client).deleteObject(argThat((DeleteObjectRequest req) -> req != null && key.equals(req.key())));

        // 4. Missing key returns null / false
        when(s3Client.headObject(argThat((HeadObjectRequest req) -> req != null && key.equals(req.key()))))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
        assertFalse(awsStorageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, phase));
    }

    // ----- plan artifacts (plan-artifacts/<organization>/<workspace>/<job>/<step>/plan-artifacts.tar.gz)

    private ListObjectsV2Response page(boolean truncated, String token, String... keys) {
        return ListObjectsV2Response.builder()
                .contents(java.util.Arrays.stream(keys).map(key -> S3Object.builder().key(key).build()).toList())
                .isTruncated(truncated)
                .nextContinuationToken(token)
                .build();
    }

    private static boolean deletesExactly(DeleteObjectsRequest request, String... keys) {
        return request.delete().objects().stream().map(ObjectIdentifier::key).toList().equals(List.of(keys));
    }

    @Test
    void deletingAJobsArtifactsRemovesOnlyThatJobsPrefix() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page(false, null, "plan-artifacts/org1/ws1/12/step-a/plan-artifacts.tar.gz"));
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());

        awsStorageTypeService.deleteJobArtifacts("org1", "ws1", 12);

        ArgumentCaptor<ListObjectsV2Request> listed = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client).listObjectsV2(listed.capture());
        // the trailing slash stops job 12 from also matching job 120
        assertEquals("plan-artifacts/org1/ws1/12/", listed.getValue().prefix());
        verify(s3Client).deleteObjects(argThat((DeleteObjectsRequest request) ->
                deletesExactly(request, "plan-artifacts/org1/ws1/12/step-a/plan-artifacts.tar.gz")));
    }

    @Test
    void deletingAJobsArtifactsWhenThereAreNoneIsHarmless() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null));

        awsStorageTypeService.deleteJobArtifacts("org1", "ws1", 12);

        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    void aPageOfArtifactsIsDeletedWithOneBatchRequest() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null,
                "plan-artifacts/org1/ws1/1/a/plan-artifacts.tar.gz",
                "plan-artifacts/org1/ws1/2/a/plan-artifacts.tar.gz",
                "plan-artifacts/org1/ws1/3/a/plan-artifacts.tar.gz"));
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());

        awsStorageTypeService.deleteJobArtifacts("org1", "ws1", 1);

        verify(s3Client, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void aBatchDeleteThatReportsErrorsIsNotTreatedAsDeleted() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page(false, null, "plan-artifacts/org1/ws1/1/a/plan-artifacts.tar.gz"));
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("plan-artifacts/org1/ws1/1/a/plan-artifacts.tar.gz").code("AccessDenied").message("denied").build())
                .build());

        assertThrows(IllegalStateException.class, () -> awsStorageTypeService.deleteJobArtifacts("org1", "ws1", 1));
    }

    @Test
    void deletingAWorkspaceAlsoRemovesItsArtifactsAcrossEveryPage() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            ListObjectsV2Request request = invocation.getArgument(0);
            if (request.prefix().equals("tfstate/org1/ws1/")) {
                return page(false, null);
            }
            return request.continuationToken() == null
                    ? page(true, "next", "plan-artifacts/org1/ws1/1/s/plan-artifacts.tar.gz")
                    : page(false, null, "plan-artifacts/org1/ws1/2/s/plan-artifacts.tar.gz");
        });

        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());

        awsStorageTypeService.deleteWorkspaceStateData("org1", "ws1");

        verify(s3Client).deleteObjects(argThat((DeleteObjectsRequest request) -> deletesExactly(request, "plan-artifacts/org1/ws1/1/s/plan-artifacts.tar.gz")));
        verify(s3Client).deleteObjects(argThat((DeleteObjectsRequest request) -> deletesExactly(request, "plan-artifacts/org1/ws1/2/s/plan-artifacts.tar.gz")));
        verify(s3Client).listObjectsV2(argThat((ListObjectsV2Request request) -> "plan-artifacts/org1/ws1/".equals(request.prefix()) && "next".equals(request.continuationToken())));
    }
}
