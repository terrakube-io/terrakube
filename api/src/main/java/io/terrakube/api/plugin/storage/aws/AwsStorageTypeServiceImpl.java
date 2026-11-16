package io.terrakube.api.plugin.storage.aws;

import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.sshd.common.util.io.IoUtils;
import io.terrakube.api.plugin.storage.StepOutputPartsReader;
import io.terrakube.api.plugin.storage.StorageRetryMetrics;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.StorageUnavailableException;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputPart;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Builder
public class AwsStorageTypeServiceImpl implements StorageTypeService {

    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    // Written by the executor (ArtifactStorageKeys): plan-artifacts/<organization>/<workspace>/<job>/<step>/...
    private static final String ARTIFACTS_WORKSPACE_FOLDER = "plan-artifacts/%s/%s/";
    private static final String ARTIFACTS_JOB_FOLDER = "plan-artifacts/%s/%s/%d/";
    private static final String BUCKET_LOCATION_OUTPUT = "tfoutput/%s/%s/%s.tfoutput";
    private static final String BUCKET_STATE_LOCATION = "tfstate/%s/%s/%s/%s/" + TERRAFORM_PLAN_FILE;

    private static final String BUCKET_STATE_JSON = "tfstate/%s/%s/state/%s.json";
    private static final String CONTEXT_JSON = "tfoutput/context/%s/context.json";

    private static final String S3_ERROR_LOG = "S3 Not found: {}";

    private static final String TERRAFORM_TAR_GZ = "content/%s/terraformContent.tar.gz";

    @NonNull
    private S3Client s3client;

    @NonNull
    private String bucketName;

    @NonNull
    private StorageRetryMetrics storageRetryMetrics;

    // NoSuchKeyException (genuinely not found) returns empty, unchanged from before. Any other
    // failure (network, auth, throttling) is retried, then thrown as StorageUnavailableException
    // instead of silently collapsing into the same empty result as a real "not found" (#3671).
    private byte[] downloadObjectFromBucket(String bucketName, String objectKey) {
        return storageRetryMetrics.withRetry("aws", "read", () -> {
            try {
                log.info("Bucket: {} Searching: {}", bucketName, objectKey);
                GetObjectRequest objectRequest = GetObjectRequest.builder()
                        .key(objectKey)
                        .bucket(bucketName)
                        .build();
                return s3client.getObject(objectRequest, ResponseTransformer.toBytes()).asByteArray();
            } catch (NoSuchKeyException e) {
                log.debug(S3_ERROR_LOG, e.getMessage());
                return new byte[0];
            }
        });
    }

    private void uploadStringToBucket(String bucketName, String blobKey, String data) {
        uploadBytesToBucket(bucketName, blobKey, data.getBytes(StandardCharsets.UTF_8), null);
    }

    // Shared by every write path (state, context, policy evaluation, and the CLI-driven
    // configuration tarball) - a failed write now fails the caller instead of logging and
    // returning as if it succeeded (#3671).
    private void uploadBytesToBucket(String bucketName, String blobKey, byte[] data, String contentType) {
        storageRetryMetrics.withRetry("aws", "write", () -> {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder().bucket(bucketName).key(blobKey);
            if (contentType != null) {
                requestBuilder.contentType(contentType);
            }
            s3client.putObject(requestBuilder.build(), RequestBody.fromBytes(data));
            log.info("Upload Object {} completed", blobKey);
            return null;
        });
    }

    @Override
    public byte[] getStepOutput(String organizationId, String jobId, String stepId) {
        return downloadObjectFromBucket(bucketName, String.format(BUCKET_LOCATION_OUTPUT, organizationId, jobId, stepId));
    }

    @Override
    public StepOutputStream getStepOutputStream(String organizationId, String jobId, String stepId, ByteRange range) {
        String key = String.format(BUCKET_LOCATION_OUTPUT, organizationId, jobId, stepId);
        try {
            GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucketName).key(key);
            if (range != null) {
                request.range(range.toHttpHeaderValue());
            }
            ResponseInputStream<GetObjectResponse> stream =
                    s3client.getObject(request.build(), ResponseTransformer.toInputStream());
            GetObjectResponse resp = stream.response();
            long partLength = resp.contentLength() == null ? -1L : resp.contentLength();
            if (range != null && resp.contentRange() != null) {
                return StepOutputStream.partial(stream, partLength, resp.contentRange(),
                        parseTotalFromContentRange(resp.contentRange()));
            }
            return StepOutputStream.of(stream, partLength, partLength);
        } catch (NoSuchKeyException e) {
            return StepOutputStream.missing();
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                return StepOutputStream.missing();
            }
            log.error("Failed to open step output stream {}: {}", key, e.getMessage());
            throw e;
        }
    }

    private static String outputPartsPrefix(String organizationId, String jobId, String stepId) {
        return String.format("tfoutput/%s/%s/%s.parts/", organizationId, jobId, stepId);
    }

    @Override
    public List<StepOutputPart> listStepOutputParts(String organizationId, String jobId, String stepId) {
        String prefix = outputPartsPrefix(organizationId, jobId, stepId);
        List<StepOutputPart> parts = new java.util.ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix);
            if (continuationToken != null) {
                request.continuationToken(continuationToken);
            }
            ListObjectsV2Response page = s3client.listObjectsV2(request.build());
            for (S3Object object : page.contents()) {
                parts.add(new StepOutputPart(object.key().substring(prefix.length()), object.size()));
            }
            continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuationToken != null);
        return parts;
    }

    @Override
    public InputStream openStepOutputPart(String organizationId, String jobId, String stepId, String partName, long offset, long length) throws IOException {
        StepOutputPartsReader.requirePartName(partName);
        return s3client.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(outputPartsPrefix(organizationId, jobId, stepId) + partName)
                .range("bytes=" + offset + "-" + (offset + length - 1))
                .build(), ResponseTransformer.toInputStream());
    }

    private static long parseTotalFromContentRange(String contentRange) {
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1L;
        }
        String tail = contentRange.substring(slash + 1).trim();
        return "*".equals(tail) ? -1L : Long.parseLong(tail);
    }

    @Override
    public byte[] getTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId) {
        return downloadObjectFromBucket(bucketName, String.format(BUCKET_STATE_LOCATION, organizationId, workspaceId, jobId, stepId));
    }

    @Override
    public byte[] getTerraformStateJson(String organizationId, String workspaceId, String stateFileName) {
        return downloadObjectFromBucket(bucketName, String.format(BUCKET_STATE_JSON, organizationId, workspaceId, stateFileName));
    }

    @Override
    public void uploadTerraformStateJson(String organizationId, String workspaceId, String stateJson, String stateJsonHistoryId) {
        String blobKey = String.format("tfstate/%s/%s/state/%s.json", organizationId, workspaceId, stateJsonHistoryId);
        log.info("terraformJsonStateFile: {}", blobKey);
        uploadStringToBucket(bucketName, blobKey, stateJson);
    }

    @Override
    public byte[] getCurrentTerraformState(String organizationId, String workspaceId) {
        return downloadObjectFromBucket(bucketName, String.format("tfstate/%s/%s/terraform.tfstate", organizationId, workspaceId));
    }

    @Override
    public void saveCurrentTerraformState(String organizationId, String workspaceId, byte[] stateData) {
        String blobKey = String.format("tfstate/%s/%s/terraform.tfstate", organizationId, workspaceId);
        log.info("Saving current terraformStateFile: {}", blobKey);
        uploadBytesToBucket(bucketName, blobKey, stateData, "application/json");
    }

    @Override
    public void deleteCurrentTerraformState(String organizationId, String workspaceId) {
        String blobKey = String.format("tfstate/%s/%s/terraform.tfstate", organizationId, workspaceId);
        log.info("Deleting current terraformStateFile: {}", blobKey);
        storageRetryMetrics.withRetry("aws", "delete", () -> {
            try {
                s3client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(blobKey).build());
            } catch (NoSuchKeyException e) {
                log.debug("Current state already absent: {}", blobKey);
            }
            return null;
        });
    }

    @Override
    public void uploadState(String organizationId, String workspaceId, String terraformState, String historyId) {
        String blobKey = String.format("tfstate/%s/%s/terraform.tfstate", organizationId, workspaceId);
        String rawBlobKey = String.format("tfstate/%s/%s/state/%s.raw.json", organizationId, workspaceId, historyId);
        log.info("terraformStateFile: {}", blobKey);
        log.info("terraformRawStateFile: {}", rawBlobKey);
        uploadStringToBucket(bucketName, blobKey, terraformState);
        uploadStringToBucket(bucketName, rawBlobKey, terraformState);
    }

    @Override
    public String saveContext(int jobId, String jobContext) {
        String blobKey = String.format(CONTEXT_JSON, jobId);
        log.info("context file to bucket: {}", String.format(CONTEXT_JSON, jobId));
        byte[] bytes = StringUtils.getBytesUtf8(jobContext);
        String utf8EncodedString = StringUtils.newStringUtf8(bytes);
        uploadStringToBucket(bucketName, blobKey, utf8EncodedString);
        return jobContext;
    }

    @Override
    public String getContext(int jobId) {
        String data;
        byte[] bytes = downloadObjectFromBucket(bucketName, String.format(CONTEXT_JSON, jobId));
        if (bytes != null && bytes.length > 0) {
            data = new String(bytes, StandardCharsets.UTF_8);
        } else {
            data = "{}";
        }
        return data;
    }

    @Override
    public void createContentFile(String contentId, InputStream inputStream) {
        String blobKey = String.format(TERRAFORM_TAR_GZ, contentId);
        log.info("context file: {}", blobKey);

        byte[] content;
        try {
            content = IoUtils.toByteArray(inputStream);
        } catch (IOException e) {
            // Reading the upload's own request body failed - can't be retried (it's a one-shot
            // stream), so fail the upload instead of silently marking it uploaded (#3671).
            throw new StorageUnavailableException("Unable to read uploaded content for " + contentId, e);
        }

        uploadBytesToBucket(bucketName, blobKey, content, "application/gzip");
    }

    @Override
    public byte[] getContentFile(String contentId) {
        byte[] bytes = downloadObjectFromBucket(bucketName, String.format(TERRAFORM_TAR_GZ, contentId));
        if (bytes != null && bytes.length > 0) {
            return bytes;
        } else {
            return "".getBytes(Charset.defaultCharset());
        }
    }

    @Override
    public void deleteModuleStorage(String organizationName, String moduleName, String providerName) {
        String registryPath = String.format("registry/%s/%s/%s/", organizationName, moduleName, providerName);
        deleteFolderFromBucket(registryPath);
    }

    @Override
    public void deleteWorkspaceOutputData(String organizationId, List<Integer> jobList) {
        for (Integer jobId : jobList) {
            String workspaceOutputFolder = String.format("tfoutput/%s/%s/", organizationId, jobId);
            // Every page: the parts of a long step can be more than one listing returns.
            deleteAllUnder(workspaceOutputFolder);
            String contextSpoolFolder = String.format("context-spool/%s/%s/", organizationId, jobId);
            deleteFolderFromBucket(contextSpoolFolder);
        }
    }

    @Override
    public void deleteWorkspaceStateData(String organizationId, String workspaceId) {
        String workspaceStateFolder = String.format("tfstate/%s/%s/", organizationId, workspaceId);
        deleteFolderFromBucket(workspaceStateFolder);
        deleteAllUnder(String.format(ARTIFACTS_WORKSPACE_FOLDER, organizationId, workspaceId));
    }

    @Override
    public void deleteJobArtifacts(String organizationId, String workspaceId, int jobId) {
        deleteAllUnder(String.format(ARTIFACTS_JOB_FOLDER, organizationId, workspaceId, jobId));
    }

    // Every page, unlike deleteFolderFromBucket: a workspace can have far more than 1000 jobs' bundles.
    private void deleteAllUnder(String prefix) {
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix);
            if (continuationToken != null) {
                request.continuationToken(continuationToken);
            }
            ListObjectsV2Response page = s3client.listObjectsV2(request.build());
            if (!page.contents().isEmpty()) {
                // One request per page (at most 1000 keys, the DeleteObjects limit) instead of one per object.
                List<ObjectIdentifier> keys = page.contents().stream()
                        .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                        .toList();
                DeleteObjectsResponse deleted = s3client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucketName).delete(Delete.builder().objects(keys).quiet(true).build()).build());
                if (deleted.hasErrors() && !deleted.errors().isEmpty()) {
                    S3Error first = deleted.errors().get(0);
                    throw new IllegalStateException("Could not delete " + deleted.errors().size() + " plan artifact(s) under "
                            + prefix + ", first " + first.key() + ": " + first.code() + " " + first.message());
                }
                log.info("Deleted {} plan artifact(s) under {}", keys.size(), prefix);
            }
            continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuationToken != null);
    }

    @Override
    public boolean migrateToOrganization(String organizationId, String workspaceId, String migrateToOrganizationId) {
        String stateFolder = "tfstate/%s/%s/";
        String outputFolder = "tfoutput/%s/%s/";
        migrateFolder(stateFolder, organizationId, workspaceId, migrateToOrganizationId);
        migrateFolder(outputFolder, organizationId, workspaceId, migrateToOrganizationId);
        return true;
    }

    private void migrateFolder(String folder, String organizationId, String workspaceId, String migrateToOrganizationId) {
        try {
            // Define source and target prefixes
            String sourcePrefix = String.format(folder, organizationId, workspaceId);
            String targetPrefix = String.format(folder, migrateToOrganizationId, workspaceId);

            log.info("Migrating from {} to {}", sourcePrefix, targetPrefix);

            // List objects under the source prefix
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(sourcePrefix)
                    .build();
            ListObjectsV2Response listResponse = s3client.listObjectsV2(listRequest);

            for (S3Object s3Object : listResponse.contents()) {
                String sourceKey = s3Object.key();
                String targetKey = sourceKey.replaceFirst(sourcePrefix, targetPrefix);

                log.info("Copying {} to {}", sourceKey, targetKey);

                // Copy each object to the new location
                CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                        .copySource(bucketName + "/" + sourceKey)
                        .bucket(bucketName)
                        .key(targetKey)
                        .build();
                s3client.copyObject(copyRequest);
            }

            log.info("Migration completed successfully from {} to {}", sourcePrefix, targetPrefix);
        } catch (Exception e) {
            log.error("Migration failed: {}", e.getMessage());
        }
    }

    private void deleteFolderFromBucket(String prefix) {
        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();
        ListObjectsV2Response listObjectsV2Response = s3client.listObjectsV2(listObjectsV2Request);
        List<S3Object> contents = listObjectsV2Response.contents();

        for (S3Object content : contents) {
            log.warn("Deleting: {}",content.key());
            s3client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(content.key()).build());
        }
    }

    @Override
    public void uploadPolicyEvaluation(String storageUri, String policyEvaluationJson) {
        log.info("Uploading policy evaluation to S3 bucket: {}, key: {}", bucketName, storageUri);
        uploadStringToBucket(bucketName, storageUri, policyEvaluationJson);
    }

    @Override
    public String getPolicyEvaluation(String storageUri) {
        byte[] bytes = downloadObjectFromBucket(bucketName, storageUri);
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return "{}";
        }
    }

    @Override
    public void deletePolicyEvaluation(String storageUri) {
        try {
            log.info("Deleting policy evaluation from S3 bucket: {}, key: {}", bucketName, storageUri);
            s3client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(storageUri).build());
        } catch (Exception e) {
            log.warn("Failed to delete policy evaluation {} from S3: {}", storageUri, e.getMessage());
        }
    }

    private static final String CONTEXT_SPOOL_LOCATION = "context-spool/%s/%s/%s-%s.json.gz";

    @Override
    public byte[] getSpooledContextPatch(String organizationId, String jobId, String stepId, String phase) {
        String key = String.format(CONTEXT_SPOOL_LOCATION, organizationId, jobId, stepId, phase);
        byte[] bytes = downloadObjectFromBucket(bucketName, key);
        return (bytes != null && bytes.length > 0) ? bytes : null;
    }

    @Override
    public void deleteSpooledContextPatch(String organizationId, String jobId, String stepId, String phase) {
        String key = String.format(CONTEXT_SPOOL_LOCATION, organizationId, jobId, stepId, phase);
        log.info("Deleting spooled context patch from S3: {}", key);
        storageRetryMetrics.withRetry("aws", "delete", () -> {
            try {
                DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build();
                s3client.deleteObject(deleteRequest);
                log.info("Successfully deleted spooled context patch {}", key);
            } catch (NoSuchKeyException e) {
                log.debug("Spooled context patch already gone: {}", key);
            }
            return null;
        });
    }

    @Override
    public boolean hasSpooledContextPatch(String organizationId, String jobId, String stepId, String phase) {
        String key = String.format(CONTEXT_SPOOL_LOCATION, organizationId, jobId, stepId, phase);
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("Error probing spooled context patch in S3: {}", e.getMessage());
            return false;
        }
    }
}
