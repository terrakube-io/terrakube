package io.terrakube.executor.plugin.tfstate.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStateMetadataService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateAutoConfiguration;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateType;
import io.terrakube.executor.service.executor.JobExecutionWatchdogApiAccess;
import io.terrakube.executor.service.workspace.security.RunnerTokenHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RunnerApiClientTest {

    HttpServer server;
    String apiUrl;
    int status = 200;
    List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RunnerTokenHolder.clear();
    }

    @Test
    void requiresAJobToken() {
        RunnerApiClient client = new RunnerApiClient(apiUrl + "/");
        assertThat(client.url("/state")).isEqualTo(apiUrl + "/tfstate/v1/runner/state");
        assertThatThrownBy(() -> client.put("/plan", new byte[0])).isInstanceOf(IllegalStateException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void nonSuccessResponsesFailLoudly() {
        RunnerTokenHolder.set("t");
        status = 403;
        RunnerApiClient client = new RunnerApiClient(apiUrl);
        assertThatThrownBy(() -> client.delete("/heartbeat")).hasMessageContaining("403");
    }

    @Test
    void unreachableApiIsAnError() {
        RunnerTokenHolder.set("t");
        RunnerApiClient client = new RunnerApiClient("http://127.0.0.1:1");
        assertThatThrownBy(() -> client.put("/plan", new byte[0])).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void heartbeatGoesThroughTheApiInHardenedMode() {
        RunnerTokenHolder.set("t");
        JobExecutionWatchdogApiAccess.beatAndRelease(new RunnerApiClient(apiUrl));
        assertThat(calls).containsExactly("PUT /tfstate/v1/runner/heartbeat", "DELETE /tfstate/v1/runner/heartbeat");
    }

    @Test
    void apiStateTypeRequiresHardenedMode() {
        TerraformStateProperties properties = new TerraformStateProperties();
        properties.setType(TerraformStateType.ApiTerraformStateImpl);
        TerraformStateAutoConfiguration configuration = new TerraformStateAutoConfiguration();
        TerrakubeClient client = mock(TerrakubeClient.class);
        TerraformStateMetadataService metadata = new TerraformStateMetadataService(new ObjectMapper());

        assertThatThrownBy(() -> configuration.terraformState(client, properties, null, null, null,
                new TerraformStatePathService(), new TerraformOutputPathService(), metadata, null, null))
                .isInstanceOf(IllegalStateException.class);

        TerraformState state = configuration.terraformState(client, properties, null, null, null,
                new TerraformStatePathService(), new TerraformOutputPathService(), metadata, null,
                new RunnerApiClientConfiguration().runnerApiClient(apiUrl));
        assertThat(state).isInstanceOf(ApiTerraformStateImpl.class);
    }
}
