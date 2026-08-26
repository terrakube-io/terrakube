package io.terrakube.executor.service.workspace.security;

public interface WorkspaceSecurity {

    void addTerraformCredentials(String workspaceId);
    String generateAccessToken(String workspaceId);

    String generateAccessToken(int minutes);
}
