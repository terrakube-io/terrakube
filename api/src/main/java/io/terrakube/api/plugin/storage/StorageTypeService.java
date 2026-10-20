package io.terrakube.api.plugin.storage;

import java.io.InputStream;
import java.util.List;

public interface StorageTypeService {

    byte[] getStepOutput(String organizationId, String jobId, String stepId);

    io.terrakube.api.plugin.storage.model.StepOutputStream getStepOutputStream(
            String organizationId, String jobId, String stepId,
            io.terrakube.api.plugin.storage.model.ByteRange range);

    byte[] getTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId);

    byte[] getTerraformStateJson(String organizationId, String workspaceId, String stateFileName);

    void uploadTerraformStateJson(String organizationId, String workspaceId, String stateJson, String stateJsonHistoryId);

    byte[] getCurrentTerraformState(String organizationId, String workspaceId);

    void uploadState(String organizationId, String workspaceId, String terraformState, String historyId);

    String saveContext(int jobId, String jobContext);

    String getContext(int jobId);

    void createContentFile(String contentId, InputStream inputStream);

    byte[] getContentFile(String contentId);

    void deleteModuleStorage(String organizationName, String moduleName, String providerName);

    void deleteWorkspaceOutputData(String organizationId, List<Integer> jobList);

    void deleteWorkspaceStateData(String organizationId, String workspaceId);

    boolean migrateToOrganization(String organizationId, String workspaceId, String migrateToOrganizationId);

    void uploadPolicyEvaluation(String storageUri, String policyEvaluationJson);

    String getPolicyEvaluation(String storageUri);

    void deletePolicyEvaluation(String storageUri);

    /**
     * Retrieve a spooled context patch envelope from object storage.
     * Path: context-spool/<organizationId>/<jobId>/<stepId>-<phase>.json.gz
     *
     * @param organizationId the organization ID
     * @param jobId          the job ID
     * @param stepId         the step ID
     * @param phase          the execution phase ("plan" or "apply")
     * @return raw gzipped bytes of the spooled patch, or null if not found
     */
    default byte[] getSpooledContextPatch(String organizationId, String jobId, String stepId, String phase) {
        return null;
    }

    /**
     * Delete a spooled context patch from object storage after successful recovery.
     *
     * @param organizationId the organization ID
     * @param jobId          the job ID
     * @param stepId         the step ID
     * @param phase          the execution phase ("plan" or "apply")
     */
    default void deleteSpooledContextPatch(String organizationId, String jobId, String stepId, String phase) {
    }

    /**
     * Probe whether a spooled context patch exists in object storage.
     *
     * @param organizationId the organization ID
     * @param jobId          the job ID
     * @param stepId         the step ID
     * @param phase          the execution phase ("plan" or "apply")
     * @return true if the spool object exists in storage
     */
    default boolean hasSpooledContextPatch(String organizationId, String jobId, String stepId, String phase) {
        return false;
    }
}
