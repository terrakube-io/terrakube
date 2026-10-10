package io.terrakube.api.plugin.security.token;

public enum InternalTokenType {
    NOT_INTERNAL,       // Real user identities: Dex OIDC, PAT, or Federated tokens
    WORKSPACE_EXECUTOR, // Per-job runner tokens (iss=TerrakubeInternal, carries workspaceId)
    EXECUTOR_SERVICE,   // Executor backend daemon (iss=TerrakubeInternal, sub=TerrakubeInternal (EXECUTOR), no workspaceId)
    REGISTRY_SERVICE,   // Registry daemon (iss=TerrakubeInternal, sub=TerrakubeInternal (REGISTRY), no workspaceId)
    UNKNOWN_INTERNAL    // Any internal token with unrecognized/missing subject or legacy "TerrakubeInternal (TOKEN)" without workspaceId (fail-closed)
}
