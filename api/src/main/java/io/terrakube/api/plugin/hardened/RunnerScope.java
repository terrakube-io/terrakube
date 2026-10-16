package io.terrakube.api.plugin.hardened;

import io.terrakube.api.plugin.security.token.RunnerToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

record RunnerScope(String organizationId, String workspaceId, String jobId, String stepId) {

    static RunnerScope of(Jwt jwt) {
        if (jwt == null || !RunnerToken.ISSUER.equals(jwt.getClaimAsString("iss"))) {
            throw new AccessDeniedException("runner token required");
        }
        return new RunnerScope(
                jwt.getClaimAsString(RunnerToken.CLAIM_ORGANIZATION_ID),
                jwt.getClaimAsString(RunnerToken.CLAIM_WORKSPACE_ID),
                jwt.getClaimAsString(RunnerToken.CLAIM_JOB_ID),
                jwt.getClaimAsString(RunnerToken.CLAIM_STEP_ID));
    }
}
