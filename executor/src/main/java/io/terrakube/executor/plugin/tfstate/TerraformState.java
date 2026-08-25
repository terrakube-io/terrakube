package io.terrakube.executor.plugin.tfstate;

import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;

public interface TerraformState {

    String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion);

    String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory);

    boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory);

    void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState);

    String saveOutput(String organizationId, String jobId, String stepId, String output, String outputError);

    /**
     * Upload a terraform/tofu binary to cloud storage for caching.
     * Fresh executor pods can restore the binary from storage instead of
     * re-downloading from HashiCorp/GitHub.
     *
     * @param version    the resolved concrete version (e.g. "1.5.7")
     * @param tofu       true for OpenTofu, false for Terraform
     * @param binaryFile the local binary executable file to upload
     * @return true if save succeeded
     */
    default boolean saveTerraformBinary(String version, boolean tofu, File binaryFile) {
        return saveTerraformBinary(version, tofu ? "tofu" : "terraform", binaryFile);
    }

    default boolean saveTerraformBinary(String version, String tool, File binaryFile) {
        return false;
    }

    default boolean downloadTerraformBinary(String version, boolean tofu, File targetFile) {
        return downloadTerraformBinary(version, tofu ? "tofu" : "terraform", targetFile);
    }

    default boolean downloadTerraformBinary(String version, String tool, File targetFile) {
        return false;
    }
}

