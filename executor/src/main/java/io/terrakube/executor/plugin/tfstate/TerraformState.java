package io.terrakube.executor.plugin.tfstate;

import io.terrakube.executor.service.mode.TerraformJob;

import io.terrakube.executor.service.logs.StepOutputBuffer;

import java.io.File;

public interface TerraformState {

    String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion);

    String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory);

    boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory);

    /**
     * Stores the plan artifacts bundle of a plan step, under its own prefix (plan-artifacts/), apart
     * from state and plan history so a lifecycle rule can expire it independently. The bundle is
     * streamed from {@code bundle}, not read into memory.
     *
     * @return where it was stored (recorded on the job so the apply step can find it), or
     *         {@code null} when this backend does not support plan artifacts.
     * @throws TerraformStateUnavailableException when the storage could not be written after retries.
     */
    default String saveArtifacts(String organizationId, String workspaceId, String jobId, String stepId, File bundle) {
        return null;
    }

    /**
     * Streams the plan artifacts bundle recorded on the job into {@code target}. The location is
     * only used if it is inside this job's own artifacts prefix, so an edited job record cannot point
     * an apply at another job's or tenant's files.
     *
     * @return the sha256 the plan step recorded for the bundle, or {@code null} when the job has no
     *         plan artifacts (nothing to restore).
     * @throws ArtifactVerificationException      when artifacts are recorded but cannot be found or are not
     *                                           this job's.
     * @throws TerraformStateUnavailableException when the storage could not be read after retries.
     */
    default String downloadArtifacts(String organizationId, String workspaceId, String jobId, File target)
            throws ArtifactVerificationException {
        return null;
    }

    /** Best-effort removal of the job's plan artifacts once its apply has finished. */
    default void deleteArtifacts(String organizationId, String workspaceId, String jobId) {
    }

    void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState);

    /**
     * Saves the output of a step, held in a buffer that can be larger than memory, followed by its error output.
     * The backends stream it to the object store, so the upload does not need the output as one String.
     */
    String saveBufferedOutput(String organizationId, String jobId, String stepId, StepOutputBuffer output, String outputError);

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

    /**
     * Spool a gzipped structured output patch envelope directly to object storage when API is unavailable.
     * Stored at deterministic path: context-spool/<organizationId>/<jobId>/<stepId>-<phase>.json.gz
     *
     * @param organizationId the organization ID
     * @param jobId          the job ID
     * @param stepId         the step ID
     * @param phase          the execution phase ("plan" or "apply")
     * @param data           the gzipped JSON patch envelope bytes
     * @return storage path or URI where spooled, or null if write failed
     */
    default String spoolContextPatch(String organizationId, String jobId, String stepId, String phase, byte[] data) {
        return null;
    }
}

