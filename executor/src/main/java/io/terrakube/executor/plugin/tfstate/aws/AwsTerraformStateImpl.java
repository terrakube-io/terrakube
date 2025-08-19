package io.terrakube.executor.plugin.tfstate.aws;

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
import io.terrakube.client.model.organization.workspace.history.History;
import io.terrakube.client.model.organization.workspace.history.HistoryAttributes;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
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
import java.util.Optional;
import java.util.UUID;
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

    @NonNull
    TerraformOutputPathService terraformOutputPathService;

    @NonNull
    TerrakubeClient terrakubeClient;

    @NonNull
    TerraformStatePathService terraformStatePathService;

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        log.info("Generating backend override file for terraform {}", terraformVersion);
        ComparableVersion version = new ComparableVersion(terraformVersion);

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
                    awsBackendHcl.appendln("    use_path_style = true");
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

            s3client.putObject(putObjectRequest, RequestBody.fromFile(tfPlanContent));

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

                        FileUtils.copyToFile(
                                new ByteArrayInputStream(data),
                                new File(workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE));
                        planExists.set(true);
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                });
        return planExists.get();
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

            s3client.putObject(putObjectRequest, RequestBody.fromString(utf8EncodedString));

            putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKeyRaw)
                    .build();

            s3client.putObject(putObjectRequest, RequestBody.fromString(rawUtf8EncodedString));

            String stateURL = terraformStatePathService.getStateJsonPath(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);

            HistoryRequest historyRequest = new HistoryRequest();
            History newHistory = new History();
            newHistory.setType("history");
            HistoryAttributes historyAttributes = new HistoryAttributes();
            historyAttributes.setOutput(stateURL);
            historyAttributes.setSerial(1);
            historyAttributes.setMd5("0");
            historyAttributes.setLineage("0");
            historyAttributes.setJobReference(terraformJob.getJobId());
            newHistory.setAttributes(historyAttributes);
            historyRequest.setData(newHistory);

            terrakubeClient.createHistory(historyRequest, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        }
    }

    private byte[] downloadObjectFromBucket(String bucketName, String objectKey) {
        byte[] data;
        try {
            log.info("Bucket: {} Searching: {}", bucketName, objectKey);

            GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .key(objectKey)
                    .bucket(bucketName)
                    .build();
            ResponseBytes<GetObjectResponse> objectBytes = s3client.getObject(objectRequest,
                    ResponseTransformer.toBytes());
            data = objectBytes.asByteArray();
        } catch (Exception e) {
            log.debug(e.getMessage());
            data = new byte[0];
        }
        return data;
    }

    @Override
    public String saveOutput(String organizationId, String jobId, String stepId, String output, String outputError) {
        String blobKey = "tfoutput/" + organizationId + "/" + jobId + "/" + stepId + ".tfoutput";
        log.info("blobKey: {}", blobKey);

        byte[] bytes = StringUtils.getBytesUtf8(output + outputError);
        String utf8EncodedString = StringUtils.newStringUtf8(bytes);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(blobKey)
                .build();

        s3client.putObject(putObjectRequest, RequestBody.fromString(utf8EncodedString));
        log.info("Upload Object {} completed", blobKey);

        return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
    }

}
