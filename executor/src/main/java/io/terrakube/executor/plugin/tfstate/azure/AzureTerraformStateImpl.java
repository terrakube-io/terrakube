package io.terrakube.executor.plugin.tfstate.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.sas.SasProtocol;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.TextStringBuilder;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.History;
import io.terrakube.client.model.organization.workspace.history.HistoryAttributes;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Builder
@Getter
@Setter
public class AzureTerraformStateImpl implements TerraformState {

    private static final String CONTAINER_NAME = "tfstate";
    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String BACKEND_FILE_NAME = "azure_backend_override.tf";
    private static final String CONTAINER_OUTPUT_NAME = "tfoutput";
    private static final String CONTAINER_BINARY_NAME = "tfbinary";
    private String resourceGroupName;
    private String storageAccountName;
    private String storageContainerName;
    private String storageAccessKey;
    @NonNull
    TerraformOutputPathService terraformOutputPathService;

    @NonNull
    BlobServiceClient blobServiceClient;

    @NonNull
    TerrakubeClient terrakubeClient;

    @NonNull
    TerraformStatePathService terraformStatePathService;

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        log.info("Generating backend override file for terraform {}", terraformVersion);
        String azureBackend = BACKEND_FILE_NAME;
        try {
            TextStringBuilder azureBackendHcl = new TextStringBuilder();
            azureBackendHcl.appendln("terraform {");
            azureBackendHcl.appendln("  backend \"azurerm\" {");
            azureBackendHcl.appendln("      resource_group_name  = \"" + resourceGroupName + "\"");
            azureBackendHcl.appendln("      storage_account_name = \"" + storageAccountName + "\"");
            azureBackendHcl.appendln("      container_name       = \"" + storageContainerName + "\"");
            azureBackendHcl.appendln("      key                  = \"" + organizationId + "/" + workspaceId + "/terraform.tfstate" + "\"");
            azureBackendHcl.appendln("      access_key           = \"" + storageAccessKey + "\"");
            azureBackendHcl.appendln("  }");
            azureBackendHcl.appendln("}");

            File azureBackendFile = new File(
                    FilenameUtils.separatorsToSystem(
                            workingDirectory.getAbsolutePath().concat("/").concat(BACKEND_FILE_NAME)
                    )
            );
            FileUtils.writeStringToFile(azureBackendFile, azureBackendHcl.toString(), Charset.defaultCharset());

        } catch (IOException e) {
            log.error(e.getMessage());
            azureBackend = null;
        }
        return azureBackend;
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);

        log.info("blobContainerClient.exists {}", blobContainerClient.exists());
        if (!blobContainerClient.exists()) {
            blobContainerClient.create();
        }
        String blobName = organizationId + "/" + workspaceId + "/" + jobId + "/" + stepId + "/" + TERRAFORM_PLAN_FILE;
        log.info("terraformStateFile: {}", blobName);
        BlobClient blobClient = blobContainerClient.getBlobClient(blobName);

        File tfPlan = new File(workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE);
        log.info("terraformStateFile Path: {} {}", workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE, tfPlan.exists());
        if (tfPlan.exists()) {
            blobClient.uploadFromFile(tfPlan.getAbsolutePath());
            return blobClient.getBlobUrl();
        } else {
            return null;
        }
    }

    @Override
    public void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState) {
        if (applyJSON != null) {
            BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);

            log.info("blobContainerClient.exists {}", blobContainerClient.exists());
            if (!blobContainerClient.exists()) {
                blobContainerClient.create();
            }
            String stateFilename = UUID.randomUUID().toString();
            String blobName = terraformJob.getOrganizationId() + "/" + terraformJob.getWorkspaceId() + "/state/" + stateFilename + ".json";
            String blobRawName = terraformJob.getOrganizationId() + "/" + terraformJob.getWorkspaceId() + "/state/" + stateFilename + ".raw.json";
            log.info("terraform state file: {}", blobName);
            log.info("terraform raw state file: {}", blobRawName);
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            BlobClient blobRawClient = blobContainerClient.getBlobClient(blobRawName);

            byte[] bytes = StringUtils.getBytesUtf8(applyJSON);
            byte[] rawBytes = StringUtils.getBytesUtf8(rawState);
            String utf8EncodedString = StringUtils.newStringUtf8(bytes);
            String rawUtf8EncodedString = StringUtils.newStringUtf8(rawBytes);
            blobClient.upload(BinaryData.fromString(utf8EncodedString));
            blobRawClient.upload(BinaryData.fromString(rawUtf8EncodedString));

            String stateURL =  terraformStatePathService.getStateJsonPath(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);

            HistoryRequest historyRequest = new HistoryRequest();
            History newHistory = new History();
            newHistory.setType("history");
            HistoryAttributes historyAttributes = new HistoryAttributes();
            historyAttributes.setOutput(stateURL);
            historyAttributes.setJobReference(terraformJob.getJobId());
            historyAttributes.setSerial(1);
            historyAttributes.setMd5("0");
            historyAttributes.setLineage("0");
            newHistory.setAttributes(historyAttributes);
            historyRequest.setData(newHistory);

            terrakubeClient.createHistory(historyRequest, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        }
    }

    @Override
    public boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        AtomicBoolean planExists = new AtomicBoolean(false);
        Optional.ofNullable(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .ifPresent(stateUrl -> {
                    try {
                        log.info("Downloading state from {}:", stateUrl);

                        URL blobURL = new URL(stateUrl);
                        String blobName = blobURL.getPath().replace("/tfstate/", "").replace("%2F","/");

                        log.info("BlobName: {}", blobName);

                        BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_NAME);
                        BlobClient blobClient = blobContainerClient.getBlobClient(blobName);

                        BlobSasPermission blobSasPermission = new BlobSasPermission().setReadPermission(true);
                        BlobServiceSasSignatureValues builder = new BlobServiceSasSignatureValues(OffsetDateTime.now().plusMinutes(5), blobSasPermission)
                                .setProtocol(SasProtocol.HTTPS_ONLY);

                        FileUtils.copyURLToFile(
                                new URL(String.format("%s?%s", blobClient.getBlobUrl(), blobClient.generateSas(builder))),
                                new File(workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE),
                                30000,
                                30000);
                        planExists.set(true);
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                });
        return planExists.get();
    }

    public String saveOutput(String organizationId, String jobId, String stepId, String output, String outputError) {
        BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_OUTPUT_NAME);

        log.info("blobContainerClient.exists {}", blobContainerClient.exists());
        if (!blobContainerClient.exists()) {
            blobContainerClient.create();
        }
        String blobName = organizationId + "/" + jobId + "/" +stepId + ".tfoutput";
        log.info("blobName: {}", blobName);
        BlobClient blobClient = blobContainerClient.getBlobClient(blobName);

        byte[] bytes = StringUtils.getBytesUtf8(output + outputError);
        String utf8EncodedString = StringUtils.newStringUtf8(bytes);
        blobClient.upload(BinaryData.fromString(utf8EncodedString));

        return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
    }

    @Override
    public boolean saveTerraformBinary(String version, boolean tofu, File binaryFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobName = product + "/" + version + "/" + product;
        log.info("Saving {} binary to Azure Blob: {}", product, blobName);
        try {
            BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_BINARY_NAME);
            if (!blobContainerClient.exists()) {
                blobContainerClient.create();
            }
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            blobClient.uploadFromFile(binaryFile.getAbsolutePath(), true);
            log.info("Successfully cached {} binary version {} in Azure Blob", product, version);
            return true;
        } catch (Exception e) {
            log.warn("Failed to cache {} binary version {} in Azure Blob: {}", product, version, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean downloadTerraformBinary(String version, boolean tofu, File targetFile) {
        String product = tofu ? "tofu" : "terraform";
        String blobName = product + "/" + version + "/" + product;
        log.info("Attempting to restore {} binary from Azure Blob: {}", product, blobName);
        try {
            BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(CONTAINER_BINARY_NAME);
            if (!blobContainerClient.exists()) {
                log.info("{} binary container does not exist in Azure Blob, no cache available", product);
                return false;
            }
            BlobClient blobClient = blobContainerClient.getBlobClient(blobName);
            if (!blobClient.exists()) {
                log.info("{} binary version {} not found in Azure Blob cache", product, version);
                return false;
            }

            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                FileUtils.forceMkdir(parentDir);
            }

            blobClient.downloadToFile(targetFile.getAbsolutePath(), true);

            if (!targetFile.setExecutable(true, true)) {
                log.warn("Failed to set executable permission on restored {} binary", product);
            }

            log.info("Successfully restored {} binary version {} from Azure Blob", product, version);
            return true;
        } catch (Exception e) {
            log.warn("Failed to restore {} binary version {} from Azure Blob: {}", product, version, e.getMessage());
            return false;
        }
    }
}