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

    java.util.Optional<byte[]> readCurrentState(String organizationId, String workspaceId);

    void uploadCurrentState(String organizationId, String workspaceId, byte[] terraformState);

    void uploadStateHistory(String organizationId, String workspaceId, String historyId, byte[] stateJson, byte[] rawState);

    void uploadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, byte[] terraformPlan);

    void uploadStepOutput(String organizationId, String jobId, String stepId, byte[] output);

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
}
