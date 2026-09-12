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
        return false;
    }

    /**
     * Download a cached terraform/tofu binary from cloud storage to a local path.
     *
     * @param version    the resolved concrete version (e.g. "1.5.7")
     * @param tofu       true for OpenTofu, false for Terraform
     * @param targetFile where to write the restored binary on the local filesystem
     * @return true if binary was found in storage and restored successfully
     */
    default boolean downloadTerraformBinary(String version, boolean tofu, File targetFile) {
        return false;
    }

    /**
     * Upload an OPA binary to cloud storage for cross-pod caching within the same architecture.
     *
     * @param version    the OPA version (e.g. "0.68.0")
     * @param os         operating system (e.g. "linux")
     * @param arch       architecture (e.g. "amd64", "arm64")
     * @param sourceFile the local binary executable file to upload
     * @return true if save succeeded
     */
    default boolean saveOpaBinary(String version, String os, String arch, File sourceFile) {
        return false;
    }

    /**
     * Download a cached OPA binary from cloud storage for the specified architecture.
     *
     * @param version    the OPA version (e.g. "0.68.0")
     * @param os         operating system (e.g. "linux")
     * @param arch       architecture (e.g. "amd64", "arm64")
     * @param targetFile where to write the restored binary on the local filesystem
     * @return true if binary was found in storage and restored successfully
     */
    default boolean downloadOpaBinary(String version, String os, String arch, File targetFile) {
        return false;
    }
}

