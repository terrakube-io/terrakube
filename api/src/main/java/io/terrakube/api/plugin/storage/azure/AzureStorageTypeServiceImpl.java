package io.terrakube.api.plugin.storage.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.StorageUnavailableException;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Builder
public class AzureStorageTypeServiceImpl implements StorageTypeService {

    private static final String CONTAINER_NAME_STATE = "tfstate";
    private static final String CONTAINER_NAME_OUTPUT = "tfoutput";
    private static final String CONTAINER_NAME_REGISTRY = "registry";

    private static final String CONTAINER_TERRAFORM_CONTENT = "content";
    private static final String CONTEXT_FILE = "context/%s/context.json";

    private static final String TERRAFORM_TAR_GZ = "content/%s/terraformContent.tar.gz";

    // Short, bounded retry for a genuine SDK/network failure on the single underlying call -
    // mirrors the AWS backend's retry window (#3671).
    private static final int STORAGE_MAX_ATTEMPTS = 3;
    private static final long[] STORAGE_BACKOFF_MILLIS = {200, 500};

    @NonNull
    BlobServiceClient blobServiceClient;

    // A 404 (genuinely not found) returns empty, unchanged from before. Any other failure
    // (network, auth, throttling) is retried, then thrown as StorageUnavailableException instead
    // of silently collapsing into the same empty result as a real "not found" (#3671).
    private byte[] downloadBlob(String containerName, String blobName) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        BlobClient blobClient = containerClient.getBlobClient(blobName);
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= STORAGE_MAX_ATTEMPTS; attempt++) {
            try {
                log.info("Searching: /{}/{}", containerName, blobName);
                return blobClient.downloadContent().toBytes();
            } catch (Exception e) {
                if (e instanceof BlobStorageException bse && bse.getStatusCode() == 404) {
                    return new byte[0];
                }
                lastFailure = e;
                if (attempt < STORAGE_MAX_ATTEMPTS) {
                    log.warn("Azure read attempt {} failed for {}/{}, retrying: {}", attempt, containerName, blobName, e.getMessage());
                    sleepBackoff(STORAGE_BACKOFF_MILLIS[attempt - 1]);
                }
            }
        }
        throw new StorageUnavailableException("Azure read failed for " + containerName + "/" + blobName, lastFailure);
    }

    // Shared by every write path (state, context, policy evaluation, and the CLI-driven
    // configuration tarball) - a failed write now fails the caller instead of logging and
    // returning as if it succeeded (#3671).
    private void uploadBlob(String containerName, String blobName, byte[] data) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= STORAGE_MAX_ATTEMPTS; attempt++) {
            try {
                if (!containerClient.exists()) {
                    containerClient.create();
                }
                BlobClient blobClient = containerClient.getBlobClient(blobName);
                blobClient.upload(BinaryData.fromBytes(data), true);
                log.info("Upload Object {} completed", blobName);
                return;
            } catch (Exception e) {
                lastFailure = e;
                if (attempt < STORAGE_MAX_ATTEMPTS) {
                    log.warn("Azure write attempt {} failed for {}/{}, retrying: {}", attempt, containerName, blobName, e.getMessage());
                    sleepBackoff(STORAGE_BACKOFF_MILLIS[attempt - 1]);
                }
            }
        }
        throw new StorageUnavailableException("Azure write failed for " + containerName + "/" + blobName, lastFailure);
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public byte[] getStepOutput(String organizationId, String jobId, String stepId) {
        return downloadBlob(CONTAINER_NAME_OUTPUT, String.format("%s/%s/%s.tfoutput", organizationId, jobId, stepId));
    }

    @Override
    public StepOutputStream getStepOutputStream(String organizationId, String jobId, String stepId, ByteRange range) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME_OUTPUT);
        BlobClient blobClient = containerClient.getBlobClient(
                String.format("%s/%s/%s.tfoutput", organizationId, jobId, stepId));
        try {
            long size = blobClient.getProperties().getBlobSize();
            if (range == null) {
                return StepOutputStream.of(blobClient.openInputStream(), size, size);
            }
            long start = range.isSuffix() ? Math.max(0, size - range.getSuffixLength()) : range.getStart();
            long endInclusive = range.isSuffix() ? size - 1
                    : (range.getEnd() >= 0 ? Math.min(range.getEnd(), size - 1) : size - 1);
            if (start >= size) {
                return StepOutputStream.missing();
            }
            long count = endInclusive - start + 1;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            blobClient.downloadStreamWithResponse(buffer, new BlobRange(start, count),
                    null, null, false, null, null);
            String contentRange = "bytes " + start + "-" + endInclusive + "/" + size;
            return StepOutputStream.partial(new ByteArrayInputStream(buffer.toByteArray()), count, contentRange, size);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404 || e.getStatusCode() == 416) {
                return StepOutputStream.missing();
            }
            throw e;
        }
    }

    @Override
    public byte[] getTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId) {
        return downloadBlob(CONTAINER_NAME_STATE, String.format("%s/%s/%s/%s/terraformLibrary.tfPlan", organizationId, workspaceId, jobId, stepId));
    }

    @Override
    public byte[] getTerraformStateJson(String organizationId, String workspaceId, String stateFileName) {
        return downloadBlob(CONTAINER_NAME_STATE, String.format("%s/%s/state/%s.json", organizationId, workspaceId, stateFileName));
    }

    @Override
    public void uploadTerraformStateJson(String organizationId, String workspaceId, String stateJson, String stateJsonHistoryId) {
        String stateFileName = String.format("%s/%s/state/%s.json", organizationId, workspaceId, stateJsonHistoryId);
        log.info("New State JSON Az Storage: {}", stateFileName);
        uploadBlob(CONTAINER_NAME_STATE, stateFileName, stateJson.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] getCurrentTerraformState(String organizationId, String workspaceId) {
        return downloadBlob(CONTAINER_NAME_STATE, String.format("%s/%s/terraform.tfstate", organizationId, workspaceId));
    }

    @Override
    public void uploadState(String organizationId, String workspaceId, String terraformState, String historyId) {
        String stateFileName = String.format("%s/%s/terraform.tfstate", organizationId, workspaceId);
        String rawStateFileName = String.format("%s/%s/state/%s.raw.json", organizationId, workspaceId, historyId);
        log.info("New State File Az Storage: {}", stateFileName);
        log.info("New State Raw File Az Storage: {}", rawStateFileName);
        byte[] data = terraformState.getBytes(StandardCharsets.UTF_8);
        uploadBlob(CONTAINER_NAME_STATE, stateFileName, data);
        uploadBlob(CONTAINER_NAME_STATE, rawStateFileName, data);
    }

    @Override
    public String saveContext(int jobId, String jobContext) {
        String blobName = String.format(CONTEXT_FILE, jobId);
        log.info("Context file: {}", blobName);
        uploadBlob(CONTAINER_NAME_OUTPUT, blobName, jobContext.getBytes(StandardCharsets.UTF_8));
        return jobContext;
    }

    @Override
    public String getContext(int jobId) {
        byte[] bytes = downloadBlob(CONTAINER_NAME_OUTPUT, String.format(CONTEXT_FILE, jobId));
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return "{}";
        }
    }

    @Override
    public void createContentFile(String contentId, InputStream inputStream) {
        String blobName = String.format(TERRAFORM_TAR_GZ, contentId);
        log.info("Content file: {}", blobName);

        byte[] content;
        try {
            content = inputStream.readAllBytes();
        } catch (IOException e) {
            // Reading the upload's own request body failed - can't be retried (it's a one-shot
            // stream), so fail the upload instead of silently marking it uploaded (#3671).
            throw new StorageUnavailableException("Unable to read uploaded content for " + contentId, e);
        }

        uploadBlob(CONTAINER_TERRAFORM_CONTENT, blobName, content);
    }

    @Override
    public byte[] getContentFile(String contentId) {
        byte[] bytes = downloadBlob(CONTAINER_TERRAFORM_CONTENT, String.format(TERRAFORM_TAR_GZ, contentId));
        if (bytes != null && bytes.length > 0) {
            return bytes;
        } else {
            return "".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void deleteModuleStorage(String organizationName, String moduleName, String providerName) {
        String moduleFolderPath = String.format("%s/%s/%s", organizationName, moduleName, providerName);
        deleteFolderFromContainer(CONTAINER_NAME_REGISTRY, moduleFolderPath);
    }

    @Override
    public void deleteWorkspaceOutputData(String organizationId, List<Integer> jobList) {
        for (Integer jobId : jobList) {
            String workspaceOutputFolder = String.format("%s/%s", organizationId, jobId);
            deleteFolderFromContainer(CONTAINER_NAME_OUTPUT, workspaceOutputFolder);
        }
    }

    @Override
    public void deleteWorkspaceStateData(String organizationId, String workspaceId) {
        String moduleFolderPath = String.format("%s/%s", organizationId, workspaceId);
        deleteFolderFromContainer(CONTAINER_NAME_STATE, moduleFolderPath);
    }

    @Override
    public boolean migrateToOrganization(String organizationId, String workspaceId, String migrateToOrganizationId) {
        migrateFolder(CONTAINER_NAME_STATE, organizationId, workspaceId, migrateToOrganizationId);
        migrateFolder(CONTAINER_NAME_OUTPUT, organizationId, workspaceId, migrateToOrganizationId);
        return true;
    }

    private void migrateFolder(String containerName, String organizationId, String workspaceId, String migrateToOrganizationId) {
        try {
            // Define source and target prefixes
            String sourcePrefix = String.format("%s/%s", organizationId, workspaceId);
            String targetPrefix = String.format("%s/%s", migrateToOrganizationId, workspaceId);

            log.info("Migrating from {} to {}", sourcePrefix, targetPrefix);

            // Get the container client for state blobs
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);

            // List objects under the source prefixed path
            ListBlobsOptions options = new ListBlobsOptions().setPrefix(sourcePrefix);
            containerClient.listBlobs(options, null).forEach(blobItem -> {
                String sourceBlobName = blobItem.getName();
                String targetBlobName = sourceBlobName.replaceFirst(sourcePrefix, targetPrefix);

                log.info("Copying {} to {}", sourceBlobName, targetBlobName);

                // Download the blob to memory
                byte[] blobContent = containerClient.getBlobClient(sourceBlobName).downloadContent().toBytes();

                // Upload it to the target location
                containerClient.getBlobClient(targetBlobName).upload(BinaryData.fromBytes(blobContent), true);
            });

            log.info("Migration completed successfully from {} to {}", sourcePrefix, targetPrefix);

        } catch (Exception e) {
            log.error("Migration failed: {}", e.getMessage());
        }
    }

    private void deleteFolderFromContainer(String containerName, String folderPath) {
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        ListBlobsOptions options = new ListBlobsOptions().setPrefix(folderPath)
                .setDetails(new BlobListDetails().setRetrieveDeletedBlobs(false).setRetrieveSnapshots(false));
        containerClient.listBlobs(options, null).iterator()
                .forEachRemaining(item -> {
                    log.warn("Deleting file: {}", item.getName());
                    containerClient.getBlobClient(item.getName()).delete();
                });
    }

    @Override
    public void uploadPolicyEvaluation(String storageUri, String policyEvaluationJson) {
        log.info("Uploading policy evaluation to Azure blob: {}", storageUri);
        uploadBlob(CONTAINER_NAME_OUTPUT, storageUri, policyEvaluationJson.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getPolicyEvaluation(String storageUri) {
        byte[] bytes = downloadBlob(CONTAINER_NAME_OUTPUT, storageUri);
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return "{}";
        }
    }

    @Override
    public void deletePolicyEvaluation(String storageUri) {
        try {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME_OUTPUT);
            if (containerClient.exists()) {
                BlobClient blobClient = containerClient.getBlobClient(storageUri);
                if (blobClient.exists()) {
                    blobClient.delete();
                    log.info("Deleted policy evaluation from Azure blob: {}", storageUri);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete policy evaluation {} from Azure: {}", storageUri, e.getMessage());
        }
    }
}
