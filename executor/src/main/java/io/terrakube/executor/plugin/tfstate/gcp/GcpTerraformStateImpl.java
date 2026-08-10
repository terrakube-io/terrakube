package io.terrakube.executor.plugin.tfstate.gcp;

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
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.artifact.ArtifactPackagingService;
import io.terrakube.executor.service.artifact.ArtifactVerifier;
import io.terrakube.executor.service.mode.TerraformJob;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Builder
public class GcpTerraformStateImpl implements TerraformState {

    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String GCP_CREDENTIALS_FILE = "GCP_CREDENTIALS_FILE.json";
    private static final String BACKEND_FILE_NAME = "gcp_backend_override.tf";
    // A separate top-level prefix from "tfstate/" (where the plan file and state live), so an
    // Object Lifecycle Management rule can expire artifacts - which can be far larger than a plan
    // file - without touching plan/state history.
    private static final String ARTIFACTS_PREFIX = "plan-artifacts/";

    @NonNull
    TerraformOutputPathService terraformOutputPathService;

    @NonNull
    private Storage storage;

    @NonNull
    private String credentials;

    @NonNull
    private String bucketName;

    @NonNull
    TerraformStatePathService terraformStatePathService;

    @NonNull TerrakubeClient terrakubeClient;

    @NonNull ArtifactVerifier artifactVerifier;

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
            gcpBackendHcl.appendln("    credentials = \"" + GCP_CREDENTIALS_FILE + "\"");
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
            FileUtils.writeStringToFile(gcpBackendCredentials, new String(Base64.decodeBase64(credentials), StandardCharsets.UTF_8), Charset.defaultCharset());
            FileUtils.writeStringToFile(gcpBackendFile, gcpBackendHcl.toString(), Charset.defaultCharset());

        } catch (IOException e) {
            log.error(e.getMessage());
            gcpBackend = null;
        }
        return gcpBackend;
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        String blobKey = String.format("tfstate/%s/%s/%s/%s/%s", organizationId, workspaceId, jobId, stepId, TERRAFORM_PLAN_FILE);
        log.info("terraformGcpStateFile: {}", blobKey);

        File tfPlanContent = new File(FilenameUtils.concat(workingDirectory.getAbsolutePath(), TERRAFORM_PLAN_FILE));
        log.info("terraformGcpStateFile Path: {} {}", workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE, tfPlanContent.exists());
        if (tfPlanContent.exists()) {
            String url = null;
            try {
                BlobId blobId = BlobId.of(bucketName, blobKey);
                BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
                storage.create(blobInfo, FileUtils.readFileToByteArray(tfPlanContent));
                url = String.format("https://storage.cloud.google.com/%s/%s", bucketName, blobKey);
                log.info("File URL {}", url);
            } catch (IOException e) {
                log.error(e.getMessage());
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
                    try {
                        log.info("Downloading state from {}:", stateUrl);
                        String buketNamePath = String.format("/%s/",bucketName);
                        log.info("Generating pre-signed URL. {}", new URL(stateUrl).getPath().replace(buketNamePath, ""));

                        // Define resource
                        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, new URL(stateUrl).getPath().replace(buketNamePath, ""))).build();

                        URL signedUrl = storage.signUrl(blobInfo, 5, TimeUnit.MINUTES);

                        log.info("Pre-Signed URL: " + signedUrl.toString());

                        FileUtils.copyURLToFile(
                                signedUrl,
                                new File(FilenameUtils.concat(workingDirectory.getAbsolutePath() , TERRAFORM_PLAN_FILE)),
                                30000,
                                30000);
                        planGcExist.set(true);
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                });
        return planGcExist.get();
    }

    @Override
    public String saveArtifacts(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        String blobKey = String.format(ARTIFACTS_PREFIX + "%s/%s/%s/%s/%s", organizationId, workspaceId, jobId, stepId, ArtifactPackagingService.ARTIFACTS_FILE_NAME);
        log.info("terraformGcpArtifactsFile: {}", blobKey);

        File artifactsTarGz = new File(FilenameUtils.concat(workingDirectory.getAbsolutePath(), ArtifactPackagingService.ARTIFACTS_FILE_NAME));
        if (artifactsTarGz.exists()) {
            try {
                BlobId blobId = BlobId.of(bucketName, blobKey);
                BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
                storage.create(blobInfo, FileUtils.readFileToByteArray(artifactsTarGz));
                return String.format("https://storage.cloud.google.com/%s/%s", bucketName, blobKey);
            } catch (IOException e) {
                log.error(e.getMessage());
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public boolean downloadArtifacts(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory)
            throws ArtifactVerificationException {
        JobAttributes attributes = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes();
        String artifactsUrl = attributes.getTerraformPlanArtifacts();
        if (artifactsUrl == null || artifactsUrl.isBlank()) {
            return false;
        }

        byte[] artifactBytes;
        try {
            log.info("Downloading plan artifacts from {}", artifactsUrl);
            String bucketNamePath = String.format("/%s/", bucketName);
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, new URL(artifactsUrl).getPath().replace(bucketNamePath, ""))).build();
            URL signedUrl = storage.signUrl(blobInfo, 5, TimeUnit.MINUTES);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = signedUrl.openStream()) {
                in.transferTo(buffer);
            }
            artifactBytes = buffer.toByteArray();
        } catch (IOException e) {
            throw new ArtifactVerificationException("Failed to download plan artifacts bundle from " + artifactsUrl + ": " + e.getMessage());
        }

        artifactVerifier.verifyAndExtract(artifactBytes, attributes.getTerraformPlanArtifactsChecksum(), workingDirectory);
        return true;
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
            storage.create(blobInfo, utf8EncodedString.getBytes());
            storage.create(rawBlobInfo, rawUtf8EncodedString.getBytes());
            log.info("File uploaded to bucket {} as {}", bucketName, blobKey);
            log.info("File uploaded to bucket {} as {}", bucketName, rawBlobKey);

            HistoryRequest historyRequest = new HistoryRequest();
            History newHistory = new History();
            newHistory.setType("history");
            HistoryAttributes historyAttributes = new HistoryAttributes();
            historyAttributes.setJobReference(terraformJob.getJobId());
            historyAttributes.setSerial(1);
            historyAttributes.setMd5("0");
            historyAttributes.setLineage("0");
            historyAttributes.setOutput(terraformStatePathService.getStateJsonPath(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename));
            newHistory.setAttributes(historyAttributes);
            historyRequest.setData(newHistory);

            terrakubeClient.createHistory(historyRequest, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        }
    }

    @Override
    public String saveOutput(String organizationId, String jobId, String stepId, String output, String outputError) {
        String blobKey = String.format("tfoutput/%s/%s/%s.tfoutput",organizationId, jobId, stepId);
        log.info("blobKey: {}", blobKey);

        byte[] bytes = StringUtils.getBytesUtf8(output + outputError);
        String utf8EncodedString = StringUtils.newStringUtf8(bytes);
        BlobId blobId = BlobId.of(bucketName, blobKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
        storage.create(blobInfo, utf8EncodedString.getBytes());
        log.info("File uploaded to bucket {} as {}", bucketName, blobKey);

        return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
    }

    @Override
    public boolean saveTerraformBinary(String version, boolean tofu, File binaryFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobKey = String.format("tfbinary/%s/%s/%s", product, version, product);
        log.info("Saving {} binary to GCS: {}", product, blobKey);
        try {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            storage.create(blobInfo, FileUtils.readFileToByteArray(binaryFile));
            log.info("Successfully cached {} binary version {} in GCS", product, version);
            return true;
        } catch (Exception e) {
            log.warn("Failed to cache {} binary version {} in GCS: {}", product, version, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean downloadTerraformBinary(String version, boolean tofu, File targetFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobKey = String.format("tfbinary/%s/%s/%s", product, version, product);
        log.info("Attempting to restore {} binary from GCS: {}", product, blobKey);
        try {
            BlobId blobId = BlobId.of(bucketName, blobKey);
            com.google.cloud.storage.Blob blob = storage.get(blobId);
            if (blob == null || !blob.exists()) {
                log.info("{} binary version {} not found in GCS cache", product, version);
                return false;
            }

            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                FileUtils.forceMkdir(parentDir);
            }

            byte[] content = storage.readAllBytes(blobId);
            FileUtils.writeByteArrayToFile(targetFile, content);

            if (!targetFile.setExecutable(true, true)) {
                log.warn("Failed to set executable permission on restored {} binary", product);
            }

            log.info("Successfully restored {} binary version {} from GCS", product, version);
            return true;
        } catch (Exception e) {
            log.warn("Failed to restore {} binary version {} from GCS: {}", product, version, e.getMessage());
            return false;
        }
    }
}
