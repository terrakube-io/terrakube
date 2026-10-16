package io.terrakube.executor.service.hardened;

import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.security.InternalSecretLoader;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateType;
import io.terrakube.executor.service.mode.TerraformJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class HardenedRunnerGuardTest {

    ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
    TerraformStateProperties state = new TerraformStateProperties();
    Map<String, String> env = new HashMap<>();
    Set<String> files = new HashSet<>();
    boolean metadata;
    String secret = "";

    @BeforeEach
    void setup() {
        flags.setEphemeral(true);
        flags.setHardened(true);
        state.setType(TerraformStateType.ApiTerraformStateImpl);
    }

    private HardenedRunnerGuard guard(boolean logViaApi) {
        return new HardenedRunnerGuard(flags, state, new InternalSecretLoader(secret, null), logViaApi,
                env::get, files::contains, host -> metadata);
    }

    @Test
    void cleanRunnerIsAdmitted() {
        assertThat(guard(true).violations(job(token(true, "ws", "1")))).isEmpty();
    }

    @Test
    void everyAmbientCredentialIsReported() {
        env.put("InternalSecret", "x");
        env.put("AWS_ROLE_ARN", "arn");
        env.put("AWS_WEB_IDENTITY_TOKEN_FILE", "/token");
        env.put("TerrakubeRedisHostname", "redis");
        files.add("/var/run/secrets/kubernetes.io/serviceaccount/token");
        metadata = true;
        secret = "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0MTI=";
        state.setType(TerraformStateType.AwsTerraformStateImpl);
        flags.setEphemeral(false);

        List<String> violations = guard(false).violations(job(token(true, "ws", "1")));

        assertThat(violations).anyMatch(v -> v.contains("InternalSecret"))
                .anyMatch(v -> v.contains("AWS_ROLE_ARN"))
                .anyMatch(v -> v.contains("AWS_WEB_IDENTITY_TOKEN_FILE"))
                .anyMatch(v -> v.contains("TerrakubeRedisHostname"))
                .anyMatch(v -> v.contains("serviceaccount/token"))
                .anyMatch(v -> v.contains("metadata"))
                .anyMatch(v -> v.contains("internal secret"))
                .anyMatch(v -> v.contains("ApiTerraformStateImpl"))
                .anyMatch(v -> v.contains("LogViaApi"))
                .anyMatch(v -> v.contains("not ephemeral"));
    }

    @Test
    void tokenMustBeHardenedAndMatchTheJob() {
        assertThat(guard(true).violations(job(null))).anyMatch(v -> v.contains("no runner token"));
        assertThat(guard(true).violations(job("garbage"))).anyMatch(v -> v.contains("malformed"));
        assertThat(guard(true).violations(job(token(false, "ws", "1")))).anyMatch(v -> v.contains("not a hardened token"));
        assertThat(guard(true).violations(job(token(true, "other", "1")))).anyMatch(v -> v.contains("workspaceId"));
        assertThat(guard(true).violations(job(token(true, "ws", "2")))).anyMatch(v -> v.contains("jobId"));
    }

    @Test
    void persistentHardenedExecutorRefusesToStart() {
        flags.setEphemeral(false);
        assertThatThrownBy(() -> guard(true).refusePersistentHardenedExecutor()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recognisesHardenedTokens() {
        assertThat(HardenedRunnerGuard.isHardenedToken(token(true, "ws", "1"))).isTrue();
        assertThat(HardenedRunnerGuard.isHardenedToken(token(false, "ws", "1"))).isFalse();
        assertThat(HardenedRunnerGuard.isHardenedToken(null)).isFalse();
    }

    private TerraformJob job(String token) {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org");
        job.setWorkspaceId("ws");
        job.setJobId("1");
        job.setStepId("step");
        job.setRunnerToken(token);
        return job;
    }

    public static String token(boolean hardened, String workspaceId, String jobId) {
        String payload = String.format(
                "{\"iss\":\"TerrakubeRunner\",\"organizationId\":\"org\",\"workspaceId\":\"%s\",\"jobId\":\"%s\",\"stepId\":\"step\",\"hardened\":%s}",
                workspaceId, jobId, hardened);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }
}
