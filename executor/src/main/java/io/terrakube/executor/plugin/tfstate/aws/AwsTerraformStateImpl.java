package io.terrakube.executor.plugin.tfstate.aws;

import io.terrakube.executor.plugin.tfstate.OutputPartNames;
import io.terrakube.executor.service.logs.StepOutputPartWriter;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.TextStringBuilder;
import org.apache.maven.artifact.versioning.ComparableVersion;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.workspace.history.History;
import io.terrakube.client.model.organization.workspace.history.HistoryAttributes;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.ArtifactStorageKeys;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.StorageRetryMetrics;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStateMetadata;
import io.terrakube.executor.plugin.tfstate.TerraformStateMetadataService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.plugin.tfstate.TerraformStateUnavailableException;
import io.terrakube.executor.service.mode.TerraformJob;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

@Builder
@Getter
@Setter
@Slf4j
public class AwsTerraformStateImpl implements TerraformState {

    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String BACKEND_FILE_NAME = "aws_backend_override.tf";

    @NonNull
    private S3Client s3client;

    @NonNull
    private String bucketName;

    private Region region;

    private String accessKey;

    private String secretKey;

    private String endpoint;

    private boolean includeBackendKeys;

    private boolean useLockfile;

    // Written into the generated backend as use_path_style. Must follow the same
    // setting as the S3 client: an object store that rejects path-style rejects
    // it for the backend too, and that failure surfaces as a client-side
    // OpenTofu error rather than a server one.
    //
    // @Builder.Default is load-bearing. Without it an unset builder field is the
    // primitive default false, which would silently turn path-style OFF for every
    // existing custom-endpoint deployment.
    @Builder.Default
    private boolean pathStyleAccessEnabled = true;

    @NonNull
    TerraformOutputPathService terraformOutputPathService;

    @NonNull
    TerrakubeClient terrakubeClient;

    @NonNull
    TerraformStatePathService terraformStatePathService;

    @Builder.Default
    private TerraformStateMetadataService terraformStateMetadataService = new TerraformStateMetadataService(null);

    @NonNull
    StorageRetryMetrics storageRetryMetrics;

    // Matches X-Range wildcards: * or major.wildcard (e.g. 1.x, 1.*, 1.X).
    // Bare x/X are intentionally excluded: TerraformDownloader rejects them as invalid.
    // Major.minor.wildcard (1.6.x) is also excluded: the version regex extracts the concrete prefix correctly.
    private static final Pattern X_RANGE_PATTERN = Pattern.compile("^\\*$|^\\d+\\.[*xX]$");

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        log.info("Generating backend override file for terraform {}", terraformVersion);
        ComparableVersion version;
        if (X_RANGE_PATTERN.matcher(terraformVersion.trim()).matches()) {
            // X-Range wildcards (*, x, 1.x, …) resolve to the latest terraform release,
            // which is always >= 1.6.0, so use the new-style endpoints block.
            log.info("X-Range wildcard detected for \"{}\", treating as latest version", terraformVersion);
            version = new ComparableVersion("9999.9999.9999");
        } else {
            Matcher versionMatcher = Pattern.compile("(\\d++\\.\\d++(?:\\.\\d++)*+)").matcher(terraformVersion);
            ComparableVersion maxVersion = null;
            while (versionMatcher.find()) {
                ComparableVersion found = new ComparableVersion(versionMatcher.group(1));
                if (maxVersion == null || found.compareTo(maxVersion) > 0) {
                    maxVersion = found;
                }
            }
            version = maxVersion != null ? maxVersion : new ComparableVersion(terraformVersion);
        }
        log.info("Terraform version resolved to \"{}\" from constraint \"{}\"", version, terraformVersion);

        String awsBackend = BACKEND_FILE_NAME;
        try {
            TextStringBuilder awsBackendHcl = new TextStringBuilder();
            awsBackendHcl.appendln("terraform {");
            awsBackendHcl.appendln("  backend \"s3\" {");
            awsBackendHcl.appendln("    bucket     = \"" + bucketName + "\"");
            awsBackendHcl.appendln("    region     = \"" + region.toString() + "\"");
            awsBackendHcl.appendln("    key        = \"tfstate/" + organizationId + "/" + workspaceId + "/terraform.tfstate" + "\"");
            if(includeBackendKeys) {
                log.info("Including backend information");
                awsBackendHcl.appendln("    access_key = \"" + accessKey + "\"");
                awsBackendHcl.appendln("    secret_key = \"" + secretKey + "\"");
            } else {
              log.warn("No including backend information");
            }

            // Native S3 locking, off by default. Turning it on makes concurrent runs on
            // the same state wait for each other, which is why it is not the default:
            // plan only jobs are allowed to run in parallel on purpose.
            // Worth enabling when something outside Terrakube writes the same state.
            if(useLockfile) {
                if(version.compareTo(new ComparableVersion("1.10.0")) >= 0){
                    awsBackendHcl.appendln("    use_lockfile = true");
                } else {
                    log.warn("use_lockfile is enabled but ignored, it requires terraform or tofu 1.10 and this job runs {}", version);
                }
            }

            if(endpoint != null){
                if(version.compareTo(new ComparableVersion("1.6.0")) < 0){
                    awsBackendHcl.appendln("    endpoint  = \"" + endpoint + "\"");
                    awsBackendHcl.appendln("    force_path_style             = true");
                } else {
                    awsBackendHcl.appendln("    endpoints = {");
                    awsBackendHcl.appendln("       s3 = \"" + endpoint + "\"");
                    awsBackendHcl.appendln("    }");
                    awsBackendHcl.appendln("    skip_requesting_account_id = true");
                    awsBackendHcl.appendln("    skip_s3_checksum = true");
                    awsBackendHcl.appendln("    use_path_style = " + pathStyleAccessEnabled);
                }

                awsBackendHcl.appendln("    skip_credentials_validation  = true");
                awsBackendHcl.appendln("    skip_metadata_api_check      = true");
                awsBackendHcl.appendln("    skip_region_validation       = true");

            }

            awsBackendHcl.appendln("  }");
            awsBackendHcl.appendln("}");

            File awsBackendFile = new File(
                    FilenameUtils.separatorsToSystem(
                            workingDirectory.getAbsolutePath().concat("/").concat(BACKEND_FILE_NAME)
                    )
            );
            FileUtils.writeStringToFile(awsBackendFile, awsBackendHcl.toString(), Charset.defaultCharset());

        } catch (IOException e) {
            log.error(e.getMessage());
            awsBackend = null;
        }
        return awsBackend;
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {

        String blobKey = "tfstate/" + organizationId + "/" + workspaceId + "/" + jobId + "/" + stepId + "/" + TERRAFORM_PLAN_FILE;
        log.info("terraformStateFile: {}", blobKey);

        File tfPlanContent = new File(workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE);
        log.info("terraformStateFile Path: {} {}", workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE, tfPlanContent.exists());
        if (tfPlanContent.exists()) {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();

            storageRetryMetrics.withRetry("aws", "plan-write", () -> s3client.putObject(putObjectRequest, RequestBody.fromFile(tfPlanContent)));

            GetUrlRequest getUrlRequest = GetUrlRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();

            URL url = s3client.utilities().getUrl(getUrlRequest);

            return url.toExternalForm();
        } else {
            return null;
        }
    }

    @Override
    public boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        AtomicBoolean planExists = new AtomicBoolean(false);
        Optional.ofNullable(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .ifPresent(stateUrl -> {
                    try {
                        log.info("Downloading state from {}", stateUrl);
                        log.info("Buket location: {}", "tfstate/" + new URL(stateUrl).getPath().split("/tfstate/")[1]);

                        byte[] data = downloadObjectFromBucket(bucketName, "tfstate/" + new URL(stateUrl).getPath().split("/tfstate/")[1]);

                        storageRetryMetrics.withRetry("aws", "plan-disk-write", () -> {
                            FileUtils.copyToFile(
                                    new ByteArrayInputStream(data),
                                    new File(workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE));
                            return null;
                        });
                        planExists.set(true);
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                });
        return planExists.get();
    }

    @Override
    public String saveArtifacts(String organizationId, String workspaceId, String jobId, String stepId, File bundle) {
        String blobKey = ArtifactStorageKeys.key(organizationId, workspaceId, jobId, stepId);
        log.info("terraformArtifactsFile: {}", blobKey);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(blobKey)
                .build();
        // Streamed from the file (not read into memory) and written over any earlier attempt, so a
        // retry after a lost response is harmless.
        storageRetryMetrics.withRetry("aws", "artifacts-write", () -> s3client.putObject(putObjectRequest, RequestBody.fromFile(bundle)));

        GetUrlRequest getUrlRequest = GetUrlRequest.builder()
                .bucket(bucketName)
                .key(blobKey)
                .build();
        return s3client.utilities().getUrl(getUrlRequest).toExternalForm();
    }

    @Override
    public String downloadArtifacts(String organizationId, String workspaceId, String jobId, File target)
            throws ArtifactVerificationException {
        JobAttributes attributes = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes();
        String location = attributes.getTerraformPlanArtifacts();
        if (location == null || location.isBlank()) {
            return null;
        }
        String objectKey = ArtifactStorageKeys.keyFromLocation(location, organizationId, workspaceId, jobId);

        log.info("Downloading plan artifacts from bucket {} key {}", bucketName, objectKey);
        GetObjectRequest objectRequest = GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();
        try {
            storageRetryMetrics.withRetry("aws", "artifacts-read", () -> {
                // toFile refuses an existing file, which a retry would otherwise leave behind half written
                Files.deleteIfExists(target.toPath());
                return s3client.getObject(objectRequest, ResponseTransformer.toFile(target.toPath()));
            });
        } catch (TerraformStateUnavailableException e) {
            if (e.getCause() instanceof NoSuchKeyException) {
                throw new ArtifactVerificationException("The plan artifacts bundle " + objectKey + " is missing from the bucket");
            }
            throw e;
        }
        return attributes.getTerraformPlanArtifactsChecksum() == null ? "" : attributes.getTerraformPlanArtifactsChecksum();
    }

    @Override
    public void deleteArtifacts(String organizationId, String workspaceId, String jobId) {
        try {
            String location = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlanArtifacts();
            if (location == null || location.isBlank()) {
                return;
            }
            String objectKey = ArtifactStorageKeys.keyFromLocation(location, organizationId, workspaceId, jobId);
            s3client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build());
        } catch (RuntimeException e) {
            // The bucket's lifecycle rule for plan-artifacts/ is the backstop.
            log.warn("Could not delete the plan artifacts of job {}: {}", jobId, e.getMessage());
        }
    }

    @Override
    public void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState) {
        if (applyJSON != null) {
            String stateFilename = UUID.randomUUID().toString();
            String blobKey = "tfstate/" + terraformJob.getOrganizationId() + "/" + terraformJob.getWorkspaceId() + "/state/" + stateFilename + ".json";
            String blobKeyRaw = "tfstate/" + terraformJob.getOrganizationId() + "/" + terraformJob.getWorkspaceId() + "/state/" + stateFilename + ".raw.json";
            log.info("terraformStateFile: {}", blobKey);
            log.info("terraformRawStateFile: {}", blobKeyRaw);

            byte[] bytes = StringUtils.getBytesUtf8(applyJSON);
            byte[] rawBytes = StringUtils.getBytesUtf8(rawState);
            String utf8EncodedString = StringUtils.newStringUtf8(bytes);
            String rawUtf8EncodedString = StringUtils.newStringUtf8(rawBytes);

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();

            storageRetryMetrics.withRetry("aws", "state-write", () -> s3client.putObject(putObjectRequest, RequestBody.fromString(utf8EncodedString)));

            PutObjectRequest putRawObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKeyRaw)
                    .build();

            storageRetryMetrics.withRetry("aws", "state-write", () -> s3client.putObject(putRawObjectRequest, RequestBody.fromString(rawUtf8EncodedString)));

            String stateURL = terraformStatePathService.getStateJsonPath(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);

            HistoryRequest historyRequest = new HistoryRequest();
            History newHistory = new History();
            newHistory.setType("history");
            HistoryAttributes historyAttributes = new HistoryAttributes();
            TerraformStateMetadata metadata = terraformStateMetadataService.extractMetadata(rawState);
            historyAttributes.setOutput(stateURL);
            historyAttributes.setSerial(metadata.getSerial());
            historyAttributes.setMd5(metadata.getMd5());
            historyAttributes.setLineage(metadata.getLineage());
            historyAttributes.setJobReference(terraformJob.getJobId());
            newHistory.setAttributes(historyAttributes);
            historyRequest.setData(newHistory);

            terrakubeClient.createHistory(historyRequest, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        }
    }

    // The caller already confirmed a plan URL is recorded before calling this, so there is no
    // legitimate "not found" case to preserve here - any exception after retries is a genuine
    // failure and must not collapse into the same empty result a real not-found would (#3671).
    private byte[] downloadObjectFromBucket(String bucketName, String objectKey) {
        return storageRetryMetrics.withRetry("aws", "plan-read", () -> {
            log.info("Bucket: {} Searching: {}", bucketName, objectKey);
            GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .key(objectKey)
                    .bucket(bucketName)
                    .build();
            ResponseBytes<GetObjectResponse> objectBytes = s3client.getObject(objectRequest,
                    ResponseTransformer.toBytes());
            return objectBytes.asByteArray();
        });
    }

    @Override
    public String saveBufferedOutput(String organizationId, String jobId, String stepId, StepOutputBuffer output, String outputError) {
        String blobKey = "tfoutput/" + organizationId + "/" + jobId + "/" + stepId + ".tfoutput";
        log.info("blobKey: {}", blobKey);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(blobKey)
                .build();
        long length = StepOutputBuffer.sizeWith(output, outputError);

        // The provider opens a new stream per attempt, so a retry re-reads the data from the start.
        storageRetryMetrics.withRetry("aws", "output-write", () -> s3client.putObject(putObjectRequest,
                RequestBody.fromContentProvider(() -> {
                    try {
                        return output.openStream(outputError);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, length, "text/plain")));
        log.info("Upload Object {} completed", blobKey);

        return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
    }

    @Override
    public boolean saveOutputPart(String organizationId, String jobId, String stepId, int partNumber, StepOutputPartWriter.PartSource data) {
        String blobKey = outputPartsPrefix(organizationId, jobId, stepId) + OutputPartNames.part(partNumber);
        PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucketName).key(blobKey).build();
        storageRetryMetrics.withRetry("aws", "output-part-write", () -> s3client.putObject(putObjectRequest,
                RequestBody.fromContentProvider(() -> {
                    try {
                        return data.open();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, data.length(), "text/plain")));
        return true;
    }

    @Override
    public void deleteOutputParts(String organizationId, String jobId, String stepId) {
        try {
            String prefix = outputPartsPrefix(organizationId, jobId, stepId);
            ListObjectsV2Response listed = s3client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).build());
            for (S3Object object : listed.contents()) {
                s3client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(object.key()).build());
            }
        } catch (RuntimeException e) {
            // Removed with the rest of the job's output when the workspace data is deleted.
            log.warn("Could not delete the output parts of step {}: {}", stepId, e.getMessage());
        }
    }

    private String outputPartsPrefix(String organizationId, String jobId, String stepId) {
        return "tfoutput/" + organizationId + "/" + jobId + "/" + OutputPartNames.folder(stepId) + "/";
    }

    @Override
    public boolean saveTerraformBinary(String version, boolean tofu, File binaryFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobKey = "tfbinary/" + product + "/" + version + "/" + product;
        log.info("Saving {} binary to S3: {}", product, blobKey);
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();

            s3client.putObject(putObjectRequest, RequestBody.fromFile(binaryFile));
            log.info("Successfully cached {} binary version {} in S3", product, version);
            return true;
        } catch (Exception e) {
            log.warn("Failed to cache {} binary version {} in S3: {}", product, version, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean downloadTerraformBinary(String version, boolean tofu, File targetFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobKey = "tfbinary/" + product + "/" + version + "/" + product;
        log.info("Attempting to restore {} binary from S3: {}", product, blobKey);
        try {
            // Check if the object exists first
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();
            s3client.headObject(headRequest);

            // Object exists, download it
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();

            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                FileUtils.forceMkdir(parentDir);
            }

            ResponseBytes<GetObjectResponse> objectBytes = s3client.getObject(getRequest,
                    ResponseTransformer.toBytes());
            FileUtils.writeByteArrayToFile(targetFile, objectBytes.asByteArray());

            if (!targetFile.setExecutable(true, true)) {
                log.warn("Failed to set executable permission on restored {} binary", product);
            }

            log.info("Successfully restored {} binary version {} from S3", product, version);
            return true;
        } catch (NoSuchKeyException e) {
            log.info("{} binary version {} not found in S3 cache", product, version);
            return false;
        } catch (Exception e) {
            log.warn("Failed to restore {} binary version {} from S3: {}", product, version, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean saveOpaBinary(String version, String os, String arch, File sourceFile) {
        String blobKey = "opa/" + version + "/" + os + "_" + arch + "/opa";
        log.info("Saving OPA binary to S3: {}", blobKey);
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();

            s3client.putObject(putObjectRequest, RequestBody.fromFile(sourceFile));
            log.info("Successfully cached OPA binary version {} ({}_{}) in S3", version, os, arch);
            return true;
        } catch (Exception e) {
            log.warn("Failed to cache OPA binary version {} ({}_{}) in S3: {}", version, os, arch, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean downloadOpaBinary(String version, String os, String arch, File targetFile) {
        String blobKey = "opa/" + version + "/" + os + "_" + arch + "/opa";
        log.info("Attempting to restore OPA binary from S3: {}", blobKey);
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();
            s3client.headObject(headRequest);

            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();

            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                FileUtils.forceMkdir(parentDir);
            }

            ResponseBytes<GetObjectResponse> objectBytes = s3client.getObject(getRequest,
                    ResponseTransformer.toBytes());
            FileUtils.writeByteArrayToFile(targetFile, objectBytes.asByteArray());

            if (!targetFile.setExecutable(true, true)) {
                log.warn("Failed to set executable permission on restored OPA binary");
            }

            log.info("Successfully restored OPA binary version {} ({}_{}) from S3", version, os, arch);
            return true;
        } catch (NoSuchKeyException e) {
            log.info("OPA binary version {} ({}_{}) not found in S3 cache", version, os, arch);
            return false;
        } catch (Exception e) {
            log.warn("Failed to restore OPA binary version {} ({}_{}) from S3: {}", version, os, arch, e.getMessage());
            return false;
        }
    }

    private static final String CONTEXT_SPOOL_LOCATION = "context-spool/%s/%s/%s-%s.json.gz";

    @Override
    public String spoolContextPatch(String organizationId, String jobId, String stepId, String phase, byte[] data) {
        String blobKey = String.format(CONTEXT_SPOOL_LOCATION, organizationId, jobId, stepId, phase);
        log.info("Spooling context patch to S3: {}", blobKey);
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .contentType("application/gzip")
                    .build();

            storageRetryMetrics.withRetry("aws", "spool-write", () -> {
                s3client.putObject(putObjectRequest, RequestBody.fromBytes(data));
                return null;
            });
            log.info("Successfully spooled context patch for job {} step {} phase {} to S3", jobId, stepId, phase);
            return blobKey;
        } catch (Exception e) {
            log.warn("Failed to spool context patch for job {} step {} phase {} to S3: {}", jobId, stepId, phase, e.getMessage());
            return null;
        }
    }
}


