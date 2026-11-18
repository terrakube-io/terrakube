package io.terrakube.executor.service.workspace.security;

public interface WorkspaceSecurity {

    void addTerraformCredentials(String workspaceId);

    default void addTerraformCredentials(String organizationId, String workspaceId, String jobId, String stepId) {
        addTerraformCredentials(workspaceId);
    }

    default void addTerraformCredentials(String organizationId, String organizationName, String workspaceId,
                                         String jobId, String stepId) {
        addTerraformCredentials(organizationId, workspaceId, jobId, stepId);
    }

    String generateAccessToken(String workspaceId);

    String generateAccessToken(int minutes);

    default String generateAccessToken(int minutes, String workspaceId) {
        return generateAccessToken(minutes);
    }

    default String generateAccessToken(int minutes, String organizationId, String workspaceId, String jobId, String stepId) {
        return generateAccessToken(minutes, workspaceId);
    }

    default void cleanTerraformCredentials() {
    }
}
