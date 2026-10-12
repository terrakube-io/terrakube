package io.terrakube.api.plugin.security.token;

import java.util.Map;

public final class InternalTokenClassifier {

    public static final String INTERNAL_ISSUER = "TerrakubeInternal";
    public static final String SUBJECT_EXECUTOR = "TerrakubeInternal (EXECUTOR)";
    public static final String SUBJECT_REGISTRY = "TerrakubeInternal (REGISTRY)";

    private InternalTokenClassifier() {
    }

    public static InternalTokenType classify(Map<String, Object> claims) {
        if (claims == null || !INTERNAL_ISSUER.equals(claims.get("iss"))) {
            return InternalTokenType.NOT_INTERNAL;
        }

        // 1. Workspace runner tokens MUST carry workspaceId
        if (claims.containsKey("workspaceId")) {
            Object wsId = claims.get("workspaceId");
            return (wsId != null && !wsId.toString().isBlank())
                    ? InternalTokenType.WORKSPACE_EXECUTOR
                    : InternalTokenType.UNKNOWN_INTERNAL;
        }

        // 2. Internal service tokens MUST carry a recognized component subject
        Object sub = claims.get("sub");
        if (SUBJECT_EXECUTOR.equals(sub)) {
            return InternalTokenType.EXECUTOR_SERVICE;
        } else if (SUBJECT_REGISTRY.equals(sub)) {
            return InternalTokenType.REGISTRY_SERVICE;
        }

        // 3. Tokens with legacy generic subject "TerrakubeInternal (TOKEN)" or any unknown subject FAIL CLOSED
        return InternalTokenType.UNKNOWN_INTERNAL;
    }
}
