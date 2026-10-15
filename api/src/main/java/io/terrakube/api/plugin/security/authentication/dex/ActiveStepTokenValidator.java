package io.terrakube.api.plugin.security.authentication.dex;

import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ActiveStepTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final StepRepository stepRepository;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        InternalTokenType tokenType = InternalTokenClassifier.classify(token.getClaims());
        if (tokenType != InternalTokenType.WORKSPACE_EXECUTOR) {
            return OAuth2TokenValidatorResult.success();
        }

        String stepId = extractStepId(token);
        if (stepId == null || stepId.isBlank()) {
            log.warn("WORKSPACE_EXECUTOR token rejected: missing stepId claim and step in jti");
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing stepId in runner token", null));
        }

        UUID stepUuid;
        try {
            stepUuid = UUID.fromString(stepId);
        } catch (IllegalArgumentException e) {
            log.warn("WORKSPACE_EXECUTOR token rejected: malformed stepId: {}", stepId);
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Malformed stepId in runner token", null));
        }

        try {
            Optional<Step> stepOptional = stepRepository.findById(stepUuid);
            if (stepOptional.isEmpty()) {
                log.warn("WORKSPACE_EXECUTOR token rejected: step {} does not exist", stepUuid);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Step not found for runner token", null));
            }

            JobStatus status = stepOptional.get().getStatus();
            if (isTerminalStatus(status)) {
                log.warn("WORKSPACE_EXECUTOR token rejected: step {} is in terminal status {}", stepUuid, status);
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Runner token is revoked: step is in terminal state (" + status + ")", null));
            }

            log.debug("WORKSPACE_EXECUTOR token validated successfully: step {} is in active status {}", stepUuid, status);
            return OAuth2TokenValidatorResult.success();
        } catch (Exception e) {
            log.error("Failed to verify step lifecycle for runner token {}: {}", stepUuid, e.getMessage(), e);
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("server_error", "Failed to verify step status", null));
        }
    }

    public static String extractStepId(Jwt token) {
        String stepId = token.getClaimAsString("stepId");
        if (stepId != null && !stepId.isBlank()) {
            return stepId.trim();
        }

        String jti = token.getId();
        if (jti != null && !jti.isBlank()) {
            jti = jti.trim();
            // In Terrakube WorkspaceSecurityImpl, jti is formatted as: jobId + "-" + stepId
            // Two standard 36-char UUIDs separated by a hyphen have length 73 with hyphen at index 36.
            if (jti.length() >= 73 && jti.charAt(36) == '-') {
                return jti.substring(37);
            }
        }
        return null;
    }

    public static boolean isTerminalStatus(JobStatus status) {
        if (status == null) {
            return true;
        }
        return switch (status) {
            case completed, failed, cancelled, rejected, noChanges, notExecuted, NeverExecuted -> true;
            case running, queue, pending, waitingApproval, approved -> false;
            default -> true; // fail-closed on unknown status
        };
    }
}
