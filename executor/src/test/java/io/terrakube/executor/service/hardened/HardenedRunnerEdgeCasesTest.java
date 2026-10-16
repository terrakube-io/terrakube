package io.terrakube.executor.service.hardened;

import com.sun.net.httpserver.HttpServer;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.security.InternalSecretLoader;
import io.terrakube.executor.plugin.tfstate.api.RunnerApiClient;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateType;
import io.terrakube.executor.service.executor.JobExecutionWatchdogApiAccess;
import io.terrakube.executor.service.workspace.security.RunnerTokenHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class HardenedRunnerEdgeCasesTest {

    @AfterEach
    void cleanup() {
        RunnerTokenHolder.clear();
    }

    @Test
    void productionGuardReadsTheRealEnvironment() {
        ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
        flags.setEphemeral(true);
        flags.setHardened(true);
        TerraformStateProperties state = new TerraformStateProperties();
        state.setType(TerraformStateType.ApiTerraformStateImpl);
        HardenedRunnerGuard guard = new HardenedRunnerGuard(flags, state, new InternalSecretLoader("", null), true);
        List<String> violations = guard.violations(null);
        assertThat(violations).contains("the job carries no runner token");
        guard.refusePersistentHardenedExecutor();
    }

    @Test
    void missingStateConfigurationIsAViolation() {
        ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
        flags.setEphemeral(true);
        HardenedRunnerGuard guard = new HardenedRunnerGuard(flags, null, null, true, name -> null, path -> false, host -> false);
        assertThat(guard.violations(null)).anyMatch(v -> v.contains("ApiTerraformStateImpl"));
        assertThat(HardenedRunnerGuard.isHardenedToken("only.two")).isFalse();
        assertThat(HardenedRunnerGuard.isHardenedToken("a.!!!.c")).isFalse();
    }

    @Test
    void metadataProbeDetectsListeningEndpoints() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            assertThat(HardenedRunnerGuard.canConnect("127.0.0.1", socket.getLocalPort())).isTrue();
        }
        assertThat(HardenedRunnerGuard.canConnect("127.0.0.1", 1)).isFalse();
    }

    @Test
    void heartbeatFailuresNeverBreakTheJob() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            RunnerTokenHolder.set("t");
            assertThatNoException().isThrownBy(() -> JobExecutionWatchdogApiAccess.beatAndRelease(
                    new RunnerApiClient("http://127.0.0.1:" + server.getAddress().getPort())));
        } finally {
            server.stop(0);
        }
    }
}
