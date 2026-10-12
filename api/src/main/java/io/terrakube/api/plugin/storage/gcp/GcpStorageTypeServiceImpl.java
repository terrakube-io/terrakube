package io.terrakube.api.plugin.storage.gcp;

import com.google.api.gax.paging.Page;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.storage.StorageRetryMetrics;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.StorageUnavailableException;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Builder
public class GcpStorageTypeServiceImpl implements StorageTypeService {

    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String GCP_LOCATION_OUTPUT = "tfoutput/%s/%s/%s.tfoutput";
    private static final String GCP_STATE_LOCATION = "tfstate/%s/%s/%s/%s/" + TERRAFORM_PLAN_FILE;
    private static final String GCP_STATE_JSON = "tfstate/%s/%s/state/%s.json";
    private static final String GCP_HISTORY_RAW_STATE = "tfstate/%s/%s/state/%s.raw.json";
    private static final String GCP_CURRENT_STATE = "tfstate/%s/%s/terraform.tfstate/default.tfstate";
    private static final String CONTEXT_JSON = "tfoutput/context/%s/context.json";

    private static final String TERRAFORM_TAR_GZ = "content/%s/terraformContent.tar.gz";

    @NonNull
    private String bucketName;
    @NonNull
    private Storage storage;
    @NonNull
    private StorageRetryMetrics storageRetryMetrics;

    // storage.get() returning null is the SDK's own not-found signal, unchanged from before. Any
    // thrown exception is a genuine failure (network, auth, throttling) - retried, then thrown as
    // StorageUnavailableException instead of silently collapsing into the same empty result as a
    // real "not found" (#3671).
    private byte[] downloadBlob(String blobKey) {
        return storageRetryMetrics.withRetry("gcp", "read", () -> {
            log.info("Searching: {}", blobKey);
            Blob blob = storage.get(BlobId.of(bucketName, blobKey));
            if (blob == null) {
                return new byte[0];
            }
            return blob.getContent();
        });
    }

    // Shared by every write path (state, context, policy evaluation, and the CLI-driven
    // configuration tarball) - a failed write now fails the caller instead of logging and
    // returning as if it succeeded (#3671).
    private void uploadBlob(String blobKey, byte[] data, String contentType) {
        storageRetryMetrics.withRetry("gcp", "write", () -> {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            Blob blob = storage.get(blobId);
            if (blob != null) {
                try (WritableByteChannel channel = blob.writer()) {
                    channel.write(ByteBuffer.wrap(data));
                }
            } else {
                BlobInfo.Builder builder = BlobInfo.newBuilder(blobId);
                if (contentType != null) {
                    builder.setContentType(contentType);
                }
                storage.create(builder.build(), data);
            }
            log.info("Upload Object {} completed", blobKey);
            return null;
        });
    }

    @Override
    public byte[] getStepOutput(String organizationId, String jobId, String stepId) {
        return downloadBlob(String.format(GCP_LOCATION_OUTPUT, organizationId, jobId, stepId));
    }

    @Override
    public StepOutputStream getStepOutputStream(String organizationId, String jobId, String stepId, ByteRange range) {
        String key = String.format(GCP_LOCATION_OUTPUT, organizationId, jobId, stepId);
        Blob blob = storage.get(BlobId.of(bucketName, key));
        if (blob == null || !blob.exists()) {
            return StepOutputStream.missing();
        }
        long size = blob.getSize();
        if (range == null) {
            return StepOutputStream.of(Channels.newInputStream(blob.reader()), size, size);
        }
        long start = range.isSuffix() ? Math.max(0, size - range.getSuffixLength()) : range.getStart();
        long endInclusive = range.isSuffix() ? size - 1
                : (range.getEnd() >= 0 ? Math.min(range.getEnd(), size - 1) : size - 1);
        if (start >= size) {
            return StepOutputStream.missing();
        }
        ReadChannel reader = blob.reader();
        try {
            reader.seek(start);
        } catch (IOException e) {
            log.error("Failed to seek GCP step output {}: {}", key, e.getMessage());
            return StepOutputStream.missing();
        }
        reader.limit(endInclusive + 1);
        String contentRange = "bytes " + start + "-" + endInclusive + "/" + size;
        return StepOutputStream.partial(Channels.newInputStream(reader), endInclusive - start + 1, contentRange, size);
    }

    @Override
    public byte[] getTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId) {
        return downloadBlob(String.format(GCP_STATE_LOCATION, organizationId, workspaceId, jobId, stepId));
    }

    @Override
    public byte[] getTerraformStateJson(String organizationId, String workspaceId, String stateFileName) {
        return downloadBlob(String.format(GCP_STATE_JSON, organizationId, workspaceId, stateFileName));
    }

    @Override
    public void uploadTerraformStateJson(String organizationId, String workspaceId, String stateJson, String stateJsonHistoryId) {
        String currentStateKey = String.format(GCP_STATE_JSON, organizationId, workspaceId, stateJsonHistoryId);
        log.info("Define new Json State File: {}", currentStateKey);
        uploadBlob(currentStateKey, stateJson.getBytes(StandardCharsets.UTF_8), null);
    }

    @Override
    public byte[] getCurrentTerraformState(String organizationId, String workspaceId) {
        return downloadBlob(String.format(GCP_CURRENT_STATE, organizationId, workspaceId));
    }

    @Override
    public void uploadState(String organizationId, String workspaceId, String terraformState, String historyId) {
        String currentStateKey = String.format(GCP_CURRENT_STATE, organizationId, workspaceId);
        String rawStateKey = String.format(GCP_HISTORY_RAW_STATE, organizationId, workspaceId, historyId);
        log.info("Define new Current State File: {}", currentStateKey);
        log.info("Define new Current Raw History State File: {}", rawStateKey);
        byte[] data = terraformState.getBytes(StandardCharsets.UTF_8);
        uploadBlob(currentStateKey, data, null);
        uploadBlob(rawStateKey, data, null);
    }

    @Override
    public String saveContext(int jobId, String jobContext) {
        String blobKey = String.format(CONTEXT_JSON, jobId);
        log.info("context file: {}", blobKey);
        uploadBlob(blobKey, jobContext.getBytes(StandardCharsets.UTF_8), null);
        return jobContext;
    }

    @Override
    public String getContext(int jobId) {
        byte[] bytes = downloadBlob(String.format(CONTEXT_JSON, jobId));
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return "{}";
        }
    }

    @Override
    public void createContentFile(String contentId, InputStream inputStream) {
        String blobKey = String.format(TERRAFORM_TAR_GZ, contentId);
        log.info("context file: {}", blobKey);

        byte[] content;
        try {
            content = inputStream.readAllBytes();
        } catch (IOException e) {
            // Reading the upload's own request body failed - can't be retried (it's a one-shot
            // stream), so fail the upload instead of silently marking it uploaded (#3671).
            throw new StorageUnavailableException("Unable to read uploaded content for " + contentId, e);
        }

        uploadBlob(blobKey, content, "application/gzip");
    }

    @Override
    public byte[] getContentFile(String contentId) {
        byte[] bytes = downloadBlob(String.format(TERRAFORM_TAR_GZ, contentId));
        if (bytes != null && bytes.length > 0) {
            return bytes;
        } else {
            return "".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void deleteModuleStorage(String organizationName, String moduleName, String providerName) {
        String modulePath = String.format("registry/%s/%s/%s/", organizationName, moduleName, providerName);
        deleteFolderFromBucket(modulePath);

    }

    @Override
    public void deleteWorkspaceOutputData(String organizationId, List<Integer> jobList) {
        for (Integer jobId : jobList) {
            String outputPath = String.format("tfoutput/%s/%s/", organizationId, jobId);
            deleteFolderFromBucket(outputPath);
        }
    }

    @Override
    public void deleteWorkspaceStateData(String organizationId, String workspaceId) {
        String outputPath = String.format("tfstate/%s/%s/", organizationId, workspaceId);
        deleteFolderFromBucket(outputPath);
    }

    @Override
    public boolean migrateToOrganization(String organizationId, String workspaceId, String migrateToOrganizationId) {
        migrateFolder(String.format("tfoutput/%s/%s", organizationId, workspaceId), bucketName, String.format("tfoutput/%s/%s", migrateToOrganizationId, workspaceId));
        migrateFolder(String.format("tfstate/%s/%s", organizationId, workspaceId), bucketName, String.format("tfstate /%s/%s", migrateToOrganizationId, workspaceId));
        return true;
    }

    public void migrateFolder(String sourceFolderPath, String destinationBucketName, String destinationFolderPath) {
        log.info("Moving files from {} to {}/{}", sourceFolderPath, destinationBucketName, destinationFolderPath);

        Page<Blob> blobs = storage.list(
                bucketName,
                Storage.BlobListOption.prefix(sourceFolderPath),
                Storage.BlobListOption.currentDirectory()
        );

        for (Blob blob : blobs.iterateAll()) {
            String sourceFileName = blob.getName();
            String destinationFileName = destinationFolderPath + sourceFileName.substring(sourceFolderPath.length());

            // Copy object from source to destination
            CopyWriter copyWriter = storage.copy(
                    Storage.CopyRequest.newBuilder()
                            .setSource(blob.getBlobId())
                            .setTarget(BlobId.of(destinationBucketName, destinationFileName))
                            .build()
            );
            log.info("Copied file: {} to {}/{}", sourceFileName, destinationBucketName, destinationFileName);
        }

        log.info("Completed moving files from {} to {}/{}", sourceFolderPath, destinationBucketName, destinationFolderPath);
    }


    private void deleteFolderFromBucket(String folderPath) {
        Page<Blob> blobs =
                storage.list(
                        bucketName,
                        Storage.BlobListOption.currentDirectory(),
                        Storage.BlobListOption.prefix(folderPath)
                );

        for (Blob blob : blobs.iterateAll()) {
            if (blob.getName().endsWith("/")) {
                deleteFolderFromBucket(blob.getName());
            } else {
                log.info("Deleting object: {}", blob.getName());
                storage.delete(blob.getBlobId());
            }

        }
    }

    @Override
    public void uploadPolicyEvaluation(String storageUri, String policyEvaluationJson) {
        log.info("Uploading policy evaluation to GCP bucket: {}, key: {}", bucketName, storageUri);
        uploadBlob(storageUri, policyEvaluationJson.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    @Override
    public String getPolicyEvaluation(String storageUri) {
        byte[] bytes = downloadBlob(storageUri);
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return "{}";
        }
    }

    @Override
    public void deletePolicyEvaluation(String storageUri) {
        try {
            log.info("Deleting policy evaluation from GCP bucket: {}, key: {}", bucketName, storageUri);
            BlobId blobId = BlobId.of(bucketName, storageUri);
            storage.delete(blobId);
        } catch (Exception e) {
            log.warn("Failed to delete policy evaluation {} from GCP: {}", storageUri, e.getMessage());
        }
    }
}
