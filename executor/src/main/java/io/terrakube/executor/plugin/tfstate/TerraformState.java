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
     * Begin a heartbeat that keeps the API-side workspace lock alive while
     * this executor holds it. Default no-op for state backends that don't
     * lock at the API layer (local/AWS/Azure/GCP).
     */
    default void startHeartbeat(String organizationId, String workspaceId) {}

    /**
     * Stop the heartbeat started by {@link #startHeartbeat(String, String)}.
     * Idempotent. Must be invoked in a {@code finally} block so a crashed
     * executor still tears the scheduler down cleanly.
     */
    default void stopHeartbeat() {}

    /**
     * Throws {@link StateUploadFailedException} when the heartbeat has been
     * failing long enough that the API lock TTL is about to (or already has)
     * elapsed. The executor calls this between polls of long-blocking
     * terraform operations so the run aborts before the API frees the lock
     * to a different caller.
     */
    default void checkHeartbeatHealth() {}
}
