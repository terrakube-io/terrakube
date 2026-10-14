package io.terrakube.api.plugin.security.authentication.dex;

import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveStepTokenValidatorTest {

    @Mock
    private StepRepository stepRepository;

    private ActiveStepTokenValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ActiveStepTokenValidator(stepRepository);
    }

    private Jwt createRunnerJwt(String stepId, String jti) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "TerrakubeInternal");
        claims.put("sub", "TerrakubeInternal (TOKEN)");
        claims.put("workspaceId", UUID.randomUUID().toString());
        claims.put("organizationId", UUID.randomUUID().toString());
        claims.put("jobId", UUID.randomUUID().toString());
        if (stepId != null) {
            claims.put("stepId", stepId);
        }

        Map<String, Object> headers = Map.of("alg", "HS256");

        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                headers,
                claims
        ) {
            @Override
            public String getId() {
                return jti;
            }
        };
    }

    private Jwt createServiceJwt(String subject) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "TerrakubeInternal");
        claims.put("sub", subject);

        Map<String, Object> headers = Map.of("alg", "HS256");

        return new Jwt(
                "service-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                headers,
                claims
        );
    }

    private Jwt createUserJwt() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://dex.example.com");
        claims.put("sub", "user@example.com");

        Map<String, Object> headers = Map.of("alg", "RS256");

        return new Jwt(
                "user-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                headers,
                claims
        );
    }

    @Test
    void validate_runningStep_returnsSuccess() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.running);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_queueStep_returnsSuccess() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.queue);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_pendingStep_returnsSuccess() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.pending);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_completedStep_returnsFailure() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.completed);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("step is in terminal state (completed)"));
    }

    @Test
    void validate_failedStep_returnsFailure() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.failed);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("step is in terminal state (failed)"));
    }

    @Test
    void validate_cancelledStep_returnsFailure() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.cancelled);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("step is in terminal state (cancelled)"));
    }

    @Test
    void validate_rejectedStep_returnsFailure() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.rejected);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("step is in terminal state (rejected)"));
    }

    @Test
    void validate_noChangesStep_returnsFailure() {
        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.noChanges);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("step is in terminal state (noChanges)"));
    }

    @Test
    void validate_stepNotFound_returnsFailure() {
        UUID stepUuid = UUID.randomUUID();
        when(stepRepository.findById(stepUuid)).thenReturn(Optional.empty());

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("Step not found for runner token"));
    }

    @Test
    void validate_missingStepIdAndJti_returnsFailure() {
        Jwt jwt = createRunnerJwt(null, null);
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("Missing stepId in runner token"));
    }

    @Test
    void validate_malformedStepId_returnsFailure() {
        Jwt jwt = createRunnerJwt("not-a-valid-uuid", null);
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("Malformed stepId in runner token"));
    }

    @Test
    void validate_jtiFallbackExtraction_runningStep_returnsSuccess() {
        UUID jobUuid = UUID.randomUUID();
        UUID stepUuid = UUID.randomUUID();
        String jti = jobUuid + "-" + stepUuid;

        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.running);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        // No stepId claim, but valid jti format
        Jwt jwt = createRunnerJwt(null, jti);
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_jtiFallbackExtraction_completedStep_returnsFailure() {
        UUID jobUuid = UUID.randomUUID();
        UUID stepUuid = UUID.randomUUID();
        String jti = jobUuid + "-" + stepUuid;

        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.completed);

        when(stepRepository.findById(stepUuid)).thenReturn(Optional.of(step));

        // No stepId claim, but valid jti format
        Jwt jwt = createRunnerJwt(null, jti);
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("step is in terminal state (completed)"));
    }

    @Test
    void validate_executorServiceToken_bypassesStepCheck_returnsSuccess() {
        Jwt jwt = createServiceJwt("TerrakubeInternal (EXECUTOR)");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_registryServiceToken_bypassesStepCheck_returnsSuccess() {
        Jwt jwt = createServiceJwt("TerrakubeInternal (REGISTRY)");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_userToken_bypassesStepCheck_returnsSuccess() {
        Jwt jwt = createUserJwt();
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validate_databaseException_failsClosed() {
        UUID stepUuid = UUID.randomUUID();
        when(stepRepository.findById(stepUuid)).thenThrow(new RuntimeException("DB Connection Error"));

        Jwt jwt = createRunnerJwt(stepUuid.toString(), "job-step-jti");
        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getErrorCode().equals("server_error"));
    }
}
