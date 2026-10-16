package io.terrakube.api.plugin.security.token;

import java.util.List;
import java.util.Map;

public final class RunnerToken {

    public static final String ISSUER = "TerrakubeRunner";
    public static final String AUDIENCE = "terrakube-runner";
    public static final String CLAIM_ORGANIZATION_ID = "organizationId";
    public static final String CLAIM_WORKSPACE_ID = "workspaceId";
    public static final String CLAIM_JOB_ID = "jobId";
    public static final String CLAIM_STEP_ID = "stepId";
    public static final String CLAIM_HARDENED = "hardened";
    public static final String CLAIM_FLOW = "flow";

    public static final List<String> REQUIRED_SCOPE_CLAIMS =
            List.of(CLAIM_ORGANIZATION_ID, CLAIM_WORKSPACE_ID, CLAIM_JOB_ID, CLAIM_STEP_ID, CLAIM_FLOW);

    private RunnerToken() {
    }

    public static boolean isRunner(Map<String, Object> claims) {
        return claims != null && ISSUER.equals(claims.get("iss"));
    }

    public static String claim(Map<String, Object> claims, String name) {
        Object value = claims == null ? null : claims.get(name);
        return value == null ? null : value.toString();
    }
}
