package io.terrakube.executor.plugin.tfstate.aws;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AwsTerraformStateImplTest {

    private S3Client s3Client;
    private TerrakubeClient terrakubeClient;
    private TerraformOutputPathService terraformOutputPathService;
    private TerraformStatePathService terraformStatePathService;
    private AwsTerraformStateImpl awsTerraformState;

    private String bucketName = "test-bucket";
    private Region region = Region.US_EAST_1;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        terraformOutputPathService = mock(TerraformOutputPathService.class);
        terraformStatePathService = mock(TerraformStatePathService.class);

        awsTerraformState = AwsTerraformStateImpl.builder()
                .s3client(s3Client)
                .bucketName(bucketName)
                .region(region)
                .terrakubeClient(terrakubeClient)
                .terraformOutputPathService(terraformOutputPathService)
                .terraformStatePathService(terraformStatePathService)
                .includeBackendKeys(false)
                .storageRetryMetrics(new io.terrakube.executor.plugin.tfstate.StorageRetryMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()))
                .build();
    }

    @Test
    void testGetBackendStateFile_LegacyVersion(@TempDir Path tempDir) throws IOException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String terraformVersion = "1.5.0";
        File workingDirectory = tempDir.toFile();

        String result = awsTerraformState.getBackendStateFile(organizationId, workspaceId, workingDirectory, terraformVersion);

        assertEquals("aws_backend_override.tf", result);
        File backendFile = new File(workingDirectory, "aws_backend_override.tf");
        assertTrue(backendFile.exists());
        String content = FileUtils.readFileToString(backendFile, Charset.defaultCharset());
        assertTrue(content.contains("bucket     = \"test-bucket\""));
        assertTrue(content.contains("region     = \"us-east-1\""));
        assertTrue(content.contains("key        = \"tfstate/org1/ws1/terraform.tfstate\""));
        assertFalse(content.contains("access_key"));
    }

    @Test
    void testGetBackendStateFile_NoLockfileByDefault(@TempDir Path tempDir) throws IOException {
        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "1.13.0");

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertFalse(content.contains("use_lockfile"), "Locking is opt in, parallel plan only jobs keep working");
    }

    @Test
    void testGetBackendStateFile_UsesLockfileWhenEnabled(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setUseLockfile(true);

        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "1.10.0");

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("use_lockfile = true"), "Native S3 locking is available from 1.10.0");
    }

    @Test
    void testGetBackendStateFile_NoLockfileBefore110(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setUseLockfile(true);

        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "1.9.8");

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertFalse(content.contains("use_lockfile"), "use_lockfile is not a valid argument before 1.10.0");
    }

    @Test
    void testGetBackendStateFile_UsesLockfileWithCustomEndpoint(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setUseLockfile(true);
        awsTerraformState.setEndpoint("http://minio:9000");

        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "1.13.0");

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("use_lockfile = true"), "Enabling it is an explicit choice, the endpoint does not override it");
    }

    @Test
    void testGetBackendStateFile_PathStyleOnByDefaultWithEndpoint(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("http://minio:9000");

        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "1.13.0");

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("use_path_style = true"), "Path style stays on unless explicitly disabled");
    }

    @Test
    void testGetBackendStateFile_PathStyleCanBeDisabled(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("https://oss-me-central-1.aliyuncs.com");
        awsTerraformState.setPathStyleAccessEnabled(false);

        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "1.13.0");

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("use_path_style = false"),
                "Stores that reject path style, such as Alibaba OSS, need virtual hosted style here too");
    }

    @Test
    void testGetBackendStateFile_PessimisticConstraintWithEndpoint(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("http://minio:9000");

        String result = awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "~>1.13.0");

        assertEquals("aws_backend_override.tf", result);
        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("endpoints = {"), "Should use new-style endpoints block for ~>1.13.0");
        assertTrue(content.contains("skip_requesting_account_id = true"), "Should include skip_requesting_account_id for ~>1.13.0");
        assertFalse(content.contains("endpoint  ="), "Should not use deprecated endpoint parameter for ~>1.13.0");
    }

    @Test
    void testGetBackendStateFile_IvyRangeWithEndpoint(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("http://minio:9000");

        String result = awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), "[1.13.0,1.14.0]");

        assertEquals("aws_backend_override.tf", result);
        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("endpoints = {"), "Should use new-style endpoints block for [1.13.0,1.14.0]");
        assertTrue(content.contains("skip_requesting_account_id = true"), "Should include skip_requesting_account_id for [1.13.0,1.14.0]");
        assertFalse(content.contains("endpoint  ="), "Should not use deprecated endpoint parameter for [1.13.0,1.14.0]");
    }

    @Test
    void testGetBackendStateFile_NewVersionWithEndpoint(@TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("http://localhost:4566");
        awsTerraformState.setIncludeBackendKeys(true);
        awsTerraformState.setAccessKey("access");
        awsTerraformState.setSecretKey("secret");
        
        String organizationId = "org1";
        String workspaceId = "ws1";
        String terraformVersion = "1.6.0";
        File workingDirectory = tempDir.toFile();

        String result = awsTerraformState.getBackendStateFile(organizationId, workspaceId, workingDirectory, terraformVersion);

        assertEquals("aws_backend_override.tf", result);
        String content = FileUtils.readFileToString(new File(workingDirectory, "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("endpoints = {"));
        assertTrue(content.contains("s3 = \"http://localhost:4566\""));
        assertTrue(content.contains("access_key = \"access\""));
        assertTrue(content.contains("secret_key = \"secret\""));
    }

    @ParameterizedTest(name = "version constraint \"{0}\" uses new endpoints block")
    @ValueSource(strings = {
        "~1.13.0",           // npm tilde
        "^1.13.0",           // npm caret
        ">=1.7.0 <1.14.0",   // primitive range (lower >= 1.6.0)
        ">=1.5.7 <1.9.0",    // primitive range straddling 1.6.0 boundary
        "[1.7.0,)",           // ivy open upper (inclusive)
        "]1.7.0,)",           // ivy open upper (exclusive)
        "(,1.14.0]",          // ivy open lower
        "1.13.0 - 1.14.0",   // npm hyphen
        "*",                  // X-Range: any version
        "1.x",                // X-Range: any 1.y version
        "1.*",                // X-Range: any 1.y version (alternative)
    })
    void testGetBackendStateFile_NewStyleConstraintUsesEndpointsBlock(String version, @TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("http://minio:9000");

        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), version);

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("endpoints = {"), "Should use new-style endpoints for: " + version);
        assertTrue(content.contains("skip_requesting_account_id = true"), "Should include skip_requesting_account_id for: " + version);
        assertFalse(content.contains("endpoint  ="), "Should not use deprecated endpoint for: " + version);
    }

    @ParameterizedTest(name = "legacy version constraint \"{0}\" uses deprecated endpoint param")
    @ValueSource(strings = {
        "~>1.5.0",           // cocoapods pessimistic (< 1.6.0)
        "[1.4.0,1.5.9]",     // ivy bounded (< 1.6.0)
        "~1.5",              // npm tilde (< 1.6.0)
        "^1.5.3",            // npm caret (< 1.6.0)
        ">=1.4.0 <1.5.9",    // primitive range (< 1.6.0)
    })
    void testGetBackendStateFile_LegacyConstraintUsesOldEndpoint(String version, @TempDir Path tempDir) throws IOException {
        awsTerraformState.setEndpoint("http://minio:9000");

        awsTerraformState.getBackendStateFile("org1", "ws1", tempDir.toFile(), version);

        String content = FileUtils.readFileToString(new File(tempDir.toFile(), "aws_backend_override.tf"), Charset.defaultCharset());
        assertTrue(content.contains("endpoint  ="), "Should use deprecated endpoint for legacy: " + version);
        assertFalse(content.contains("endpoints = {"), "Should not use new-style endpoints for legacy: " + version);
    }

    @Test
    void testSaveTerraformPlan(@TempDir Path tempDir) throws MalformedURLException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();
        File planFile = new File(workingDirectory, "terraformLibrary.tfPlan");
        try {
            FileUtils.writeStringToFile(planFile, "plan-content", Charset.defaultCharset());
        } catch (IOException e) {
            fail("Could not create plan file");
        }

        S3Utilities s3Utilities = mock(S3Utilities.class);
        when(s3Client.utilities()).thenReturn(s3Utilities);
        when(s3Utilities.getUrl(any(GetUrlRequest.class))).thenReturn(new URL("https://s3.amazonaws.com/test-bucket/plan"));

        String url = awsTerraformState.saveTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertEquals("https://s3.amazonaws.com/test-bucket/plan", url);
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testDownloadTerraformPlan(@TempDir Path tempDir) throws MalformedURLException {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();
        
        when(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .thenReturn("https://s3.amazonaws.com/test-bucket/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan");
        
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn("plan-content".getBytes());
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class))).thenReturn(responseBytes);

        boolean result = awsTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);

        assertTrue(result);
        File downloadedPlan = new File(workingDirectory, "terraformLibrary.tfPlan");
        assertTrue(downloadedPlan.exists());
    }

    // #3671 follow-up: a genuine S3 failure must retry then throw instead of silently writing
    // empty bytes and reporting the plan as downloaded.
    @Test
    void testDownloadTerraformPlan_ThrowsAfterRetryingAGenuineFailure(@TempDir Path tempDir) {
        String organizationId = "org1";
        String workspaceId = "ws1";
        String jobId = "job1";
        String stepId = "step1";
        File workingDirectory = tempDir.toFile();

        when(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .thenReturn("https://s3.amazonaws.com/test-bucket/tfstate/org1/ws1/job1/step1/terraformLibrary.tfPlan");
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(S3Exception.builder().message("Service unavailable").build());

        assertThrows(io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException.class,
                () -> awsTerraformState.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory));

        verify(s3Client, times(3)).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    void testSaveStateJson() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org1");
        job.setWorkspaceId("ws1");
        job.setJobId("job1");
        
        String applyJSON = "{\"state\": \"applied\"}";
        String rawState = "{\"state\": \"raw\"}";

        when(terraformStatePathService.getStateJsonPath(anyString(), anyString(), anyString())).thenReturn("http://api/state.json");

        awsTerraformState.saveStateJson(job, applyJSON, rawState);

        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(terrakubeClient).createHistory(any(HistoryRequest.class), eq("org1"), eq("ws1"));
    }

    @Test
    void testSaveOutput() throws MalformedURLException {
        String organizationId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String output = "output-content";
        String outputError = "error-content";

        S3Utilities s3Utilities = mock(S3Utilities.class);
        when(s3Client.utilities()).thenReturn(s3Utilities);
        when(s3Utilities.getUrl(any(GetUrlRequest.class))).thenReturn(new URL("https://s3.amazonaws.com/test-bucket/output"));
        when(terraformOutputPathService.getOutputPath(organizationId, jobId, stepId)).thenReturn("http://api/output");

        String result = awsTerraformState.saveBufferedOutput(organizationId, jobId, stepId, StepOutputBuffer.ofMessage(output), outputError);

        assertEquals("http://api/output", result);
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testSaveBufferedOutputStreamsToStorage() throws Exception {
        when(terraformOutputPathService.getOutputPath("org1", "job1", "step1")).thenReturn("http://api/output");
        java.io.ByteArrayOutputStream uploaded = new java.io.ByteArrayOutputStream();
        long[] declaredLength = new long[1];
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            declaredLength[0] = body.contentLength();
            try (java.io.InputStream in = body.contentStreamProvider().newStream()) {
                in.transferTo(uploaded);
            }
            return PutObjectResponse.builder().build();
        });

        try (io.terrakube.executor.service.logs.StepOutputBuffer buffer = new io.terrakube.executor.service.logs.StepOutputBuffer(4, null)) {
            buffer.appendln("output-content");
            String result = awsTerraformState.saveBufferedOutput("org1", "job1", "step1", buffer, "error-content");

            assertEquals("http://api/output", result);
        }
        assertEquals("output-content\nerror-content", uploaded.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(uploaded.size(), declaredLength[0]);
    }

    @Test
    void testSaveOutputPartGoesUnderThePartsFolderOfTheStep() throws Exception {
        java.io.ByteArrayOutputStream uploaded = new java.io.ByteArrayOutputStream();
        long[] declaredLength = new long[1];
        org.mockito.ArgumentCaptor<PutObjectRequest> request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        when(s3Client.putObject(request.capture(), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            declaredLength[0] = body.contentLength();
            try (java.io.InputStream in = body.contentStreamProvider().newStream()) {
                in.transferTo(uploaded);
            }
            return PutObjectResponse.builder().build();
        });

        assertTrue(awsTerraformState.saveOutputPart("org1", "job1", "step1", 7, PART_SOURCE));

        assertEquals("tfoutput/org1/job1/step1.parts/000007.part", request.getValue().key());
        assertEquals("test-bucket", request.getValue().bucket());
        assertEquals("part-content", uploaded.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(12, declaredLength[0]);
    }

    @Test
    void testDeleteOutputPartsRemovesOnlyThePartsOfTheStep() {
        when(s3Client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class))).thenReturn(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder().contents(
                        software.amazon.awssdk.services.s3.model.S3Object.builder().key("tfoutput/org1/job1/step1.parts/000001.part").build(),
                        software.amazon.awssdk.services.s3.model.S3Object.builder().key("tfoutput/org1/job1/step1.parts/000002.part").build()).build());

        awsTerraformState.deleteOutputParts("org1", "job1", "step1");

        org.mockito.ArgumentCaptor<software.amazon.awssdk.services.s3.model.ListObjectsV2Request> listed = org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class);
        verify(s3Client).listObjectsV2(listed.capture());
        assertEquals("tfoutput/org1/job1/step1.parts/", listed.getValue().prefix());
        org.mockito.ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> deleted = org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(deleted.capture());
        assertEquals("tfoutput/org1/job1/step1.parts/000001.part", deleted.getAllValues().get(0).key());
        assertEquals("tfoutput/org1/job1/step1.parts/000002.part", deleted.getAllValues().get(1).key());
    }

    @Test
    void testDeleteOutputPartsDoesNotThrowWhenStorageFails() {
        when(s3Client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class))).thenThrow(new RuntimeException("down"));

        awsTerraformState.deleteOutputParts("org1", "job1", "step1");
    }

    private static final io.terrakube.executor.service.logs.StepOutputPartWriter.PartSource PART_SOURCE = new io.terrakube.executor.service.logs.StepOutputPartWriter.PartSource() {
            public java.io.InputStream open() {
                return new java.io.ByteArrayInputStream("part-content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            public long length() {
                return 12;
            }
        };

    @Test
    void testSpoolContextPatch() {
        String orgId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String phase = "plan";
        byte[] gzippedData = "dummy-data".getBytes();

        org.mockito.ArgumentCaptor<PutObjectRequest> putCaptor = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);

        String result = awsTerraformState.spoolContextPatch(orgId, jobId, stepId, phase, gzippedData);

        assertEquals("context-spool/org1/job1/step1-plan.json.gz", result);
        verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
        PutObjectRequest captured = putCaptor.getValue();
        assertEquals("test-bucket", captured.bucket());
        assertEquals("context-spool/org1/job1/step1-plan.json.gz", captured.key());
        assertEquals("application/gzip", captured.contentType());
        assertNull(captured.contentEncoding());
    }

    @Test
    void testSpoolContextPatch_RetriesOnFailure() {
        String orgId = "org1";
        String jobId = "job1";
        String stepId = "step1";
        String phase = "plan";
        byte[] gzippedData = "dummy-data".getBytes();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("503 Service Unavailable").build());

        String result = awsTerraformState.spoolContextPatch(orgId, jobId, stepId, phase, gzippedData);

        assertNull(result);
        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ----- plan artifacts

    private static final String ARTIFACT_KEY = "plan-artifacts/org1/ws1/job1/step1/plan-artifacts.tar.gz";

    private io.terrakube.client.model.organization.job.JobAttributes recordedArtifacts(String location, String checksum) {
        io.terrakube.client.model.organization.job.JobAttributes attributes = new io.terrakube.client.model.organization.job.JobAttributes();
        attributes.setTerraformPlanArtifacts(location);
        attributes.setTerraformPlanArtifactsChecksum(checksum);
        when(terrakubeClient.getJobById("org1", "job1").getData().getAttributes()).thenReturn(attributes);
        return attributes;
    }

    private GetObjectResponse respondWith(org.mockito.invocation.InvocationOnMock invocation, byte[] bytes) throws Exception {
        ResponseTransformer<GetObjectResponse, GetObjectResponse> transformer = invocation.getArgument(1);
        return transformer.transform(GetObjectResponse.builder().build(),
                software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes)));
    }

    @Test
    void savingArtifactsStreamsTheBundleToItsOwnPrefixAndReturnsWhereItIs(@TempDir Path tempDir) throws Exception {
        File bundle = new File(tempDir.toFile(), "bundle.tar.gz");
        FileUtils.writeStringToFile(bundle, "tar-gz-bytes", Charset.defaultCharset());
        S3Utilities s3Utilities = mock(S3Utilities.class);
        when(s3Client.utilities()).thenReturn(s3Utilities);
        when(s3Utilities.getUrl(any(GetUrlRequest.class))).thenReturn(new URL("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY));

        String location = awsTerraformState.saveArtifacts("org1", "ws1", "job1", "step1", bundle);

        assertEquals("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY, location);
        org.mockito.ArgumentCaptor<PutObjectRequest> request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertEquals("test-bucket", request.getValue().bucket());
        assertEquals(ARTIFACT_KEY, request.getValue().key());
    }

    @Test
    void savingArtifactsRetriesATransientFailure(@TempDir Path tempDir) throws Exception {
        File bundle = new File(tempDir.toFile(), "bundle.tar.gz");
        FileUtils.writeStringToFile(bundle, "x", Charset.defaultCharset());
        S3Utilities s3Utilities = mock(S3Utilities.class);
        when(s3Client.utilities()).thenReturn(s3Utilities);
        when(s3Utilities.getUrl(any(GetUrlRequest.class))).thenReturn(new URL("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("503 slow down").build())
                .thenReturn(PutObjectResponse.builder().build());

        assertNotNull(awsTerraformState.saveArtifacts("org1", "ws1", "job1", "step1", bundle));

        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void savingArtifactsFailsLoudlyWhenTheBucketStaysDown(@TempDir Path tempDir) throws Exception {
        File bundle = new File(tempDir.toFile(), "bundle.tar.gz");
        FileUtils.writeStringToFile(bundle, "x", Charset.defaultCharset());
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("down").build());

        assertThrows(io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException.class,
                () -> awsTerraformState.saveArtifacts("org1", "ws1", "job1", "step1", bundle));
    }

    @Test
    void downloadingArtifactsWritesTheBundleToTheTargetAndReturnsTheRecordedChecksum(@TempDir Path tempDir) throws Exception {
        recordedArtifacts("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY, "abc123");
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenAnswer(invocation -> respondWith(invocation, "bundle-bytes".getBytes()));
        File target = new File(tempDir.toFile(), "downloaded.tar.gz");

        String checksum = awsTerraformState.downloadArtifacts("org1", "ws1", "job1", target);

        assertEquals("abc123", checksum);
        assertEquals("bundle-bytes", FileUtils.readFileToString(target, Charset.defaultCharset()));
        org.mockito.ArgumentCaptor<GetObjectRequest> request = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(request.capture(), any(ResponseTransformer.class));
        assertEquals(ARTIFACT_KEY, request.getValue().key());
    }

    @Test
    void aRetriedDownloadReplacesAHalfWrittenFile(@TempDir Path tempDir) throws Exception {
        recordedArtifacts("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY, "abc123");
        File target = new File(tempDir.toFile(), "downloaded.tar.gz");
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenAnswer(invocation -> {
                    FileUtils.writeStringToFile(target, "partial", Charset.defaultCharset());
                    throw S3Exception.builder().message("connection reset").build();
                })
                .thenAnswer(invocation -> respondWith(invocation, "complete".getBytes()));

        awsTerraformState.downloadArtifacts("org1", "ws1", "job1", target);

        assertEquals("complete", FileUtils.readFileToString(target, Charset.defaultCharset()));
    }

    @Test
    void aJobWithoutArtifactsHasNothingToDownload(@TempDir Path tempDir) {
        recordedArtifacts(null, null);
        assertNull(awsTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
        recordedArtifacts("  ", null);
        assertNull(awsTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));

        verifyNoInteractions(s3Client);
    }

    @Test
    void artifactsRecordedWithoutAChecksumAreReportedAsAnEmptyChecksum(@TempDir Path tempDir) {
        recordedArtifacts("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY, null);
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenAnswer(invocation -> respondWith(invocation, "x".getBytes()));

        assertEquals("", awsTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
    }

    @Test
    void aRecordedLocationInAnotherJobOrTenantIsRefusedWithoutTouchingTheBucket(@TempDir Path tempDir) {
        recordedArtifacts("https://s3.amazonaws.com/test-bucket/plan-artifacts/other-org/ws1/job1/step1/plan-artifacts.tar.gz", "abc");

        assertThrows(io.terrakube.executor.plugin.tfstate.ArtifactVerificationException.class,
                () -> awsTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
        verifyNoInteractions(s3Client);
    }

    @Test
    void aBundleMissingFromTheBucketIsReportedAsMissingNotAsAnOutage(@TempDir Path tempDir) {
        recordedArtifacts("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY, "abc123");
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());

        io.terrakube.executor.plugin.tfstate.ArtifactVerificationException thrown = assertThrows(
                io.terrakube.executor.plugin.tfstate.ArtifactVerificationException.class,
                () -> awsTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
        assertTrue(thrown.getMessage().contains("missing"));
    }

    @Test
    void aBucketOutageWhileDownloadingIsNotMistakenForNoArtifacts(@TempDir Path tempDir) {
        recordedArtifacts("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY, "abc123");
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(S3Exception.builder().message("503").build());

        assertThrows(io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException.class,
                () -> awsTerraformState.downloadArtifacts("org1", "ws1", "job1", new File(tempDir.toFile(), "x")));
    }

    @Test
    void deletingArtifactsRemovesTheRecordedBundleAndNeverFailsTheCaller() {
        recordedArtifacts("https://s3.amazonaws.com/test-bucket/" + ARTIFACT_KEY, "abc123");

        awsTerraformState.deleteArtifacts("org1", "ws1", "job1");

        org.mockito.ArgumentCaptor<DeleteObjectRequest> request = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertEquals(ARTIFACT_KEY, request.getValue().key());

        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().message("denied").build());
        assertDoesNotThrow(() -> awsTerraformState.deleteArtifacts("org1", "ws1", "job1"));
    }
}
