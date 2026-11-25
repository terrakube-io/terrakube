package io.terrakube.executor.plugin.tfstate.gcp;

import io.terrakube.executor.plugin.tfstate.OutputPartNames;
import io.terrakube.executor.service.logs.StepOutputPartWriter;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.TextStringBuilder;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.workspace.history.History;
import io.terrakube.client.model.organization.workspace.history.HistoryAttributes;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.ArtifactStorageKeys;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.BinaryCacheFileSupport;
import io.terrakube.executor.plugin.tfstate.StorageRetryMetrics;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStateMetadata;
import io.terrakube.executor.plugin.tfstate.TerraformStateMetadataService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException;
import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Builder
public class GcpTerraformStateImpl implements TerraformState {

    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String TERRAFORM_PLAN_JSON_FILE = "plan.json";
    private static final String GCP_CREDENTIALS_FILE = "GCP_CREDENTIALS_FILE.json";
    private static final String BACKEND_FILE_NAME = "gcp_backend_override.tf";
    private static final String CONTEXT_SPOOL_LOCATION = "context-spool/%s/%s/%s-%s.json.gz";

    @NonNull
    TerraformOutputPathService terraformOutputPathService;

    @NonNull
    private Storage storage;

    /**
     * Base64 encoded service account key. Blank when the executor runs with Application Default
     * Credentials, in which case Terraform's gcs backend uses them as well.
     */
    private String credentials;

    @NonNull
    private String bucketName;

    @NonNull
    TerraformStatePathService terraformStatePathService;

    @NonNull TerrakubeClient terrakubeClient;

    @Builder.Default
    private TerraformStateMetadataService terraformStateMetadataService = new TerraformStateMetadataService(null);

    @NonNull
    StorageRetryMetrics storageRetryMetrics;

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        log.info("Generating backend override file for terraform {}", terraformVersion);
        String gcpBackend = BACKEND_FILE_NAME;
        try {
            TextStringBuilder gcpBackendHcl = new TextStringBuilder();
            gcpBackendHcl.appendln("terraform {");
            gcpBackendHcl.appendln("  backend \"gcs\" {");
            gcpBackendHcl.appendln("    bucket      = \"" + bucketName + "\"");
            gcpBackendHcl.appendln("    prefix      = \"tfstate/" + organizationId + "/" + workspaceId + "/terraform.tfstate" + "\"");
            if (hasCredentialsKey()) {
                gcpBackendHcl.appendln("    credentials = \"" + GCP_CREDENTIALS_FILE + "\"");
            }
            gcpBackendHcl.appendln("  }");
            gcpBackendHcl.appendln("}");

            File gcpBackendCredentials = new File(
                    FilenameUtils.separatorsToSystem(
                            workingDirectory.getAbsolutePath().concat("/").concat(GCP_CREDENTIALS_FILE)
                    )
            );

            File gcpBackendFile = new File(
                    FilenameUtils.separatorsToSystem(
                            workingDirectory.getAbsolutePath().concat("/").concat(BACKEND_FILE_NAME)
                    )
            );
            // Without a key, Terraform's gcs backend falls back to Application Default Credentials.
            if (hasCredentialsKey()) {
                FileUtils.writeStringToFile(gcpBackendCredentials, new String(Base64.decodeBase64(credentials), StandardCharsets.UTF_8), Charset.defaultCharset());
            }
            FileUtils.writeStringToFile(gcpBackendFile, gcpBackendHcl.toString(), Charset.defaultCharset());

        } catch (IOException e) {
            log.error(e.getMessage());
            gcpBackend = null;
        }
        return gcpBackend;
    }

    private boolean hasCredentialsKey() {
        return credentials != null && !credentials.isBlank();
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        String blobKey = String.format("tfstate/%s/%s/%s/%s/%s", organizationId, workspaceId, jobId, stepId, TERRAFORM_PLAN_FILE);
        log.info("terraformGcpStateFile: {}", blobKey);

        File tfPlanContent = new File(FilenameUtils.concat(workingDirectory.getAbsolutePath(), TERRAFORM_PLAN_FILE));
        log.info("terraformGcpStateFile Path: {} {}", workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE, tfPlanContent.exists());
        if (tfPlanContent.exists()) {
            byte[] content;
            try {
                content = FileUtils.readFileToByteArray(tfPlanContent);
            } catch (IOException e) {
                // Reading the plan file we just wrote locally failed - can't be retried
                // meaningfully, so fail the save instead of silently reporting none happened (#3671).
                throw new TerraformStateUnavailableException("Unable to read local plan file " + tfPlanContent.getAbsolutePath(), e);
            }

            byte[] jsonContent = null;
            File tfPlanJson = new File(workingDirectory, TERRAFORM_PLAN_JSON_FILE);
            if (tfPlanJson.exists()) {
                try {
                    jsonContent = FileUtils.readFileToByteArray(tfPlanJson);
                } catch (IOException e) {
                    throw new TerraformStateUnavailableException("Unable to read local plan JSON file " + tfPlanJson.getAbsolutePath(), e);
                }
            }

            BlobId blobId = BlobId.of(bucketName, blobKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            String url = storageRetryMetrics.withRetry("gcp", "plan-write", () -> {
                storage.create(blobInfo, content);
                String planUrl = String.format("https://storage.cloud.google.com/%s/%s", bucketName, blobKey);
                log.info("File URL {}", planUrl);
                return planUrl;
            });
            if (jsonContent != null) {
                byte[] planJsonBytes = jsonContent;
                BlobInfo jsonBlobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, blobKey + ".json")).build();
                storageRetryMetrics.withRetry("gcp", "plan-json-write", () -> storage.create(jsonBlobInfo, planJsonBytes));
            }
            return url;
        } else {
            return null;
        }
    }

    @Override
    public boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        AtomicBoolean planGcExist = new AtomicBoolean(false);
        Optional.ofNullable(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .ifPresent(stateUrl -> {
                    log.info("Downloading state from {}:", stateUrl);

                    // The caller already confirmed a plan URL is recorded before calling this, so
                    // there is no legitimate "not found" case to preserve here - any exception
                    // after retries is a genuine failure (#3671).
                    storageRetryMetrics.withRetry("gcp", "plan-read", () -> {
                        String buketNamePath = String.format("/%s/", bucketName);
                        String blobPath = new URL(stateUrl).getPath().replace(buketNamePath, "");
                        BlobId blobId = BlobId.of(bucketName, blobPath);
                        log.info("Downloading Terraform plan blob from gs://{}/{}", bucketName, blobPath);
                        File targetFile = new File(FilenameUtils.concat(workingDirectory.getAbsolutePath(), TERRAFORM_PLAN_FILE));
                        Blob blob = storage.get(blobId);
                        if (blob == null) {
                            throw new IllegalStateException("Terraform plan not found at gs://" + bucketName + "/" + blobPath);
                        }
                        blob.downloadTo(targetFile.toPath());
                        return null;
                    });
                    planGcExist.set(true);
                });
        return planGcExist.get();
    }

    @Override
    public boolean downloadTerraformPlanJson(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        String stateUrl = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan();
        if (stateUrl == null || stateUrl.isBlank()) {
            return false;
        }
        String blobPath;
        try {
            String bucketNamePath = String.format("/%s/", bucketName);
            blobPath = new URL(stateUrl).getPath().replace(bucketNamePath, "") + ".json";
        } catch (IOException e) {
            log.error(e.getMessage());
            return false;
        }
        BlobId blobId = BlobId.of(bucketName, blobPath);
        log.info("Downloading Terraform plan JSON blob from gs://{}/{}", bucketName, blobPath);
        Blob blob = storageRetryMetrics.withRetry("gcp", "plan-json-read", () -> storage.get(blobId));
        if (blob == null) {
            return false;
        }
        File targetFile = new File(workingDirectory, TERRAFORM_PLAN_JSON_FILE);
        storageRetryMetrics.withRetry("gcp", "plan-json-read", () -> {
            blob.downloadTo(targetFile.toPath());
            return null;
        });
        return true;
    }

    @Override
    public String saveArtifacts(String organizationId, String workspaceId, String jobId, String stepId, File bundle) {
        String blobKey = ArtifactStorageKeys.key(organizationId, workspaceId, jobId, stepId);
        log.info("terraformGcpArtifactsFile: {}", blobKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, blobKey)).build();
        // createFrom streams the file in chunks (resumable upload) instead of reading it into memory,
        // and replaces any earlier attempt.
        storageRetryMetrics.withRetry("gcp", "artifacts-write", () -> storage.createFrom(blobInfo, bundle.toPath()));
        return String.format("https://storage.cloud.google.com/%s/%s", bucketName, blobKey);
    }

    @Override
    public String downloadArtifacts(String organizationId, String workspaceId, String jobId, File target)
            throws ArtifactVerificationException {
        JobAttributes attributes = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes();
        String location = attributes.getTerraformPlanArtifacts();
        if (location == null || location.isBlank()) {
            return null;
        }
        String blobKey = ArtifactStorageKeys.keyFromLocation(location, organizationId, workspaceId, jobId);

        log.info("Downloading plan artifacts from bucket {} object {}", bucketName, blobKey);
        com.google.cloud.storage.Blob blob = storageRetryMetrics.withRetry("gcp", "artifacts-read", () -> storage.get(BlobId.of(bucketName, blobKey)));
        if (blob == null) {
            throw new ArtifactVerificationException("The plan artifacts bundle " + blobKey + " is missing from the bucket");
        }
        storageRetryMetrics.withRetry("gcp", "artifacts-read", () -> {
            blob.downloadTo(target.toPath());
            return null;
        });
        return attributes.getTerraformPlanArtifactsChecksum() == null ? "" : attributes.getTerraformPlanArtifactsChecksum();
    }

    @Override
    public void deleteArtifacts(String organizationId, String workspaceId, String jobId) {
        try {
            String location = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlanArtifacts();
            if (location == null || location.isBlank()) {
                return;
            }
            String blobKey = ArtifactStorageKeys.keyFromLocation(location, organizationId, workspaceId, jobId);
            storage.delete(BlobId.of(bucketName, blobKey));
        } catch (RuntimeException e) {
            // The bucket's lifecycle rule for plan-artifacts/ is the backstop.
            log.warn("Could not delete the plan artifacts of job {}: {}", jobId, e.getMessage());
        }
    }

    @Override
    public void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState) {
        if (applyJSON != null) {
            String stateFilename = UUID.randomUUID().toString();
            String blobKey = String.format("tfstate/%s/%s/state/%s.json", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);
            String rawBlobKey = String.format("tfstate/%s/%s/state/%s.raw.json", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);
            log.info("terraformGcpStateFile: {}", blobKey);
            log.info("terraformGcpRawStateFile: {}", rawBlobKey);

            String utf8EncodedString = StringUtils.newStringUtf8(StringUtils.getBytesUtf8(applyJSON));
            String rawUtf8EncodedString = StringUtils.newStringUtf8(StringUtils.getBytesUtf8(rawState));

            BlobId blobId = BlobId.of(bucketName, blobKey);
            BlobId rawBlobId = BlobId.of(bucketName, rawBlobKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            BlobInfo rawBlobInfo = BlobInfo.newBuilder(rawBlobId).build();
            storageRetryMetrics.withRetry("gcp", "state-write", () -> storage.create(blobInfo, utf8EncodedString.getBytes()));
            storageRetryMetrics.withRetry("gcp", "state-write", () -> storage.create(rawBlobInfo, rawUtf8EncodedString.getBytes()));
            log.info("File uploaded to bucket {} as {}", bucketName, blobKey);
            log.info("File uploaded to bucket {} as {}", bucketName, rawBlobKey);

            HistoryRequest historyRequest = new HistoryRequest();
            History newHistory = new History();
            newHistory.setType("history");
            HistoryAttributes historyAttributes = new HistoryAttributes();
            TerraformStateMetadata metadata = terraformStateMetadataService.extractMetadata(rawState);
            historyAttributes.setJobReference(terraformJob.getJobId());
            historyAttributes.setSerial(metadata.getSerial());
            historyAttributes.setMd5(metadata.getMd5());
            historyAttributes.setLineage(metadata.getLineage());
            historyAttributes.setOutput(terraformStatePathService.getStateJsonPath(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename));
            newHistory.setAttributes(historyAttributes);
            historyRequest.setData(newHistory);

            terrakubeClient.createHistory(historyRequest, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        }
    }

    @Override
    public String saveBufferedOutput(String organizationId, String jobId, String stepId, StepOutputBuffer output, String outputError) {
        String blobKey = String.format("tfoutput/%s/%s/%s.tfoutput", organizationId, jobId, stepId);
        log.info("blobKey: {}", blobKey);

        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, blobKey)).build();
        storageRetryMetrics.withRetry("gcp", "output-write", () -> {
            try (java.io.InputStream data = output.openStream(outputError)) {
                return storage.createFrom(blobInfo, data);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        log.info("File uploaded to bucket {} as {}", bucketName, blobKey);

        return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
    }

    @Override
    public boolean saveOutputPart(String organizationId, String jobId, String stepId, int partNumber, StepOutputPartWriter.PartSource data) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, outputPartsPrefix(organizationId, jobId, stepId) + OutputPartNames.part(partNumber))).build();
        storageRetryMetrics.withRetry("gcp", "output-part-write", () -> {
            try (java.io.InputStream in = data.open()) {
                return storage.createFrom(blobInfo, in);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return true;
    }

    @Override
    public void deleteOutputParts(String organizationId, String jobId, String stepId) {
        try {
            String prefix = outputPartsPrefix(organizationId, jobId, stepId);
            List<BlobId> parts = new ArrayList<>();
            for (com.google.cloud.storage.Blob blob : storage.list(bucketName, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
                parts.add(blob.getBlobId());
            }
            if (!parts.isEmpty()) {
                storage.delete(parts);
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete the output parts of step {}: {}", stepId, e.getMessage());
        }
    }

    private String outputPartsPrefix(String organizationId, String jobId, String stepId) {
        return String.format("tfoutput/%s/%s/%s/", organizationId, jobId, OutputPartNames.folder(stepId));
    }

    @Override
    public boolean saveTerraformBinary(String version, boolean tofu, String os, String arch, File binaryFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobKey = String.format("tfbinary/%s/%s/%s_%s/%s", product, version, os, arch, product);
        return saveCachedBinary(product, blobKey, version, os, arch, binaryFile);
    }

    @Override
    public boolean downloadTerraformBinary(String version, boolean tofu, String os, String arch, File targetFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobKey = String.format("tfbinary/%s/%s/%s_%s/%s", product, version, os, arch, product);
        return downloadCachedBinary(product, blobKey, version, os, arch, targetFile);
    }

    @Override
    public boolean saveOpaBinary(String version, String os, String arch, File sourceFile) {
        String blobKey = String.format("opa/%s/%s_%s/opa", version, os, arch);
        return saveCachedBinary("OPA", blobKey, version, os, arch, sourceFile);
    }

    @Override
    public boolean downloadOpaBinary(String version, String os, String arch, File targetFile) {
        String blobKey = String.format("opa/%s/%s_%s/opa", version, os, arch);
        return downloadCachedBinary("OPA", blobKey, version, os, arch, targetFile);
    }

    @Override
    public boolean saveInfracostBinary(String version, String os, String arch, File sourceFile) {
        return saveCachedBinary("Infracost", infracostBinaryBlobKey(version, os, arch), version, os, arch, sourceFile);
    }

    @Override
    public boolean downloadInfracostBinary(String version, String os, String arch, File targetFile) {
        return downloadCachedBinary("Infracost", infracostBinaryBlobKey(version, os, arch), version, os, arch, targetFile);
    }

    private boolean saveCachedBinary(String label, String blobKey, String version, String os, String arch, File sourceFile) {
        log.info("Saving {} binary to GCS: {}", label, blobKey);
        try {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            storage.createFrom(blobInfo, sourceFile.toPath());
            log.info("Successfully cached {} binary version {} ({}_{}) in GCS", label, version, os, arch);
            return true;
        } catch (Exception e) {
            log.warn("Failed to cache {} binary version {} ({}_{}) in GCS: {}", label, version, os, arch, e.getMessage());
            return false;
        }
    }

    private boolean downloadCachedBinary(String label, String blobKey, String version, String os, String arch, File targetFile) {
        log.info("Attempting to restore {} binary from GCS: {}", label, blobKey);
        Path tempFile = null;
        try {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            com.google.cloud.storage.Blob blob = storage.get(blobId);
            if (blob == null || !blob.exists()) {
                log.info("{} binary version {} ({}_{}) not found in GCS cache", label, version, os, arch);
                return false;
            }

            tempFile = BinaryCacheFileSupport.prepareTempTarget(targetFile);
            storage.downloadTo(blobId, tempFile);
            BinaryCacheFileSupport.finalizeDownloadedBinary(tempFile, targetFile, label);

            log.info("Successfully restored {} binary version {} ({}_{}) from GCS", label, version, os, arch);
            return true;
        } catch (Exception e) {
            log.warn("Failed to restore {} binary version {} ({}_{}) from GCS: {}", label, version, os, arch, e.getMessage());
            return false;
        } finally {
            BinaryCacheFileSupport.deleteQuietly(tempFile);
        }
    }

    @Override
    public boolean saveInfracostChecksum(String version, String os, String arch, String sha256Hex) {
        String blobKey = infracostChecksumBlobKey(version, os, arch);
        log.info("Saving Infracost SHA-256 checksum to GCS: {}", blobKey);
        try {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            storage.create(blobInfo, sha256Hex.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            log.warn("Failed to cache Infracost SHA-256 checksum for version {} ({}_{}) in GCS: {}", version, os, arch, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<String> downloadInfracostChecksum(String version, String os, String arch) {
        String blobKey = infracostChecksumBlobKey(version, os, arch);
        try {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            com.google.cloud.storage.Blob blob = storage.get(blobId);
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            String value = new String(storage.readAllBytes(blobId), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (Exception e) {
            log.warn("Failed to restore Infracost SHA-256 checksum for version {} ({}_{}) from GCS: {}", version, os, arch, e.getMessage());
            return Optional.empty();
        }
    }

    private static String infracostBinaryBlobKey(String version, String os, String arch) {
        return String.format("infracost/%s/%s_%s/infracost", version, os, arch);
    }

    private static String infracostChecksumBlobKey(String version, String os, String arch) {
        return infracostBinaryBlobKey(version, os, arch) + ".sha256";
    }

    @Override
    public String spoolContextPatch(String organizationId, String jobId, String stepId, String phase, byte[] data) {
        String blobKey = String.format(CONTEXT_SPOOL_LOCATION, organizationId, jobId, stepId, phase);
        log.info("Spooling context patch to GCS: {}", blobKey);
        try {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("application/gzip")
                    .build();
            storageRetryMetrics.withRetry("gcp", "spool-write", () -> storage.create(blobInfo, data));
            log.info("Successfully spooled context patch for job {} step {} phase {} to GCS", jobId, stepId, phase);
            return blobKey;
        } catch (Exception e) {
            log.error("Failed to spool context patch to GCS: {}", e.getMessage(), e);
            return null;
        }
    }
}

