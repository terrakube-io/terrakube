package io.terrakube.executor.plugin.tfstate.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStateMetadataService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.RunnerTokenHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiTerraformStateImplTest {

    @TempDir
    File workingDirectory;

    HttpServer server;
    String apiUrl;
    List<String> calls = new CopyOnWriteArrayList<>();
    Map<String, byte[]> bodies = new ConcurrentHashMap<>();
    TerrakubeClient terrakubeClient = mock(TerrakubeClient.class);
    ApiTerraformStateImpl state;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(key + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.put(key, exchange.getRequestBody().readAllBytes());
            byte[] response = key.startsWith("GET") ? "PLAN".getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
            if (response.length > 0) {
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RunnerTokenHolder.set("runner.jwt.token");

        TerraformStatePathService statePaths = new TerraformStatePathService();
        ReflectionTestUtils.setField(statePaths, "apiUrl", apiUrl);
        TerraformOutputPathService outputPaths = new TerraformOutputPathService();
        ReflectionTestUtils.setField(outputPaths, "apiUrl", apiUrl);
        state = ApiTerraformStateImpl.builder()
                .runnerApiClient(new RunnerApiClient(apiUrl))
                .terrakubeClient(terrakubeClient)
                .terraformStatePathService(statePaths)
                .terraformOutputPathService(outputPaths)
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RunnerTokenHolder.clear();
    }

    @Test
    void backendPointsAtTheRunnerEndpointsWithTheJobToken() throws Exception {
        state.getBackendStateFile("org", "ws", workingDirectory, "1.9.0");
        String hcl = Files.readString(new File(workingDirectory, ApiTerraformStateImpl.BACKEND_FILE_NAME).toPath());
        assertThat(hcl).contains("backend \"http\"")
                .contains("address        = \"" + apiUrl + "/tfstate/v1/runner/state\"")
                .contains("lock_address   = \"" + apiUrl + "/tfstate/v1/runner/lock\"")
                .contains("lock_method    = \"POST\"")
                .contains("unlock_method  = \"DELETE\"")
                .doesNotContain("password")
                .doesNotContain("runner.jwt.token")
                .doesNotContain("access_key")
                .doesNotContain("bucket");
    }

    @Test
    void planOutputAndHistoryGoThroughTheApi() throws Exception {
        Files.writeString(new File(workingDirectory, "terraformLibrary.tfPlan").toPath(), "PLAN");
        String planUrl = state.saveTerraformPlan("org", "ws", "7", "s1", workingDirectory);
        assertThat(planUrl).isEqualTo(apiUrl + "/tfstate/v1/organization/org/workspace/ws/jobId/7/step/s1/terraform.tfstate");

        String outputUrl = state.saveOutput("org", "7", "s1", "out", "err");
        assertThat(outputUrl).startsWith(apiUrl + "/tfoutput/v1/organization/org/job/7/step/s1");

        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org");
        job.setWorkspaceId("ws");
        job.setJobId("7");
        state.saveStateJson(job, "{\"values\":{}}", "{\"serial\":3,\"lineage\":\"l\"}");

        assertThat(calls).anyMatch(c -> c.equals("PUT /tfstate/v1/runner/plan Bearer runner.jwt.token"))
                .anyMatch(c -> c.equals("PUT /tfstate/v1/runner/output Bearer runner.jwt.token"))
                .anyMatch(c -> c.equals("POST /tfstate/v1/runner/history Bearer runner.jwt.token"));
        assertThat(new String(bodies.get("PUT /tfstate/v1/runner/output"), StandardCharsets.UTF_8)).isEqualTo("outerr");
        assertThat(new ObjectMapper().readTree(bodies.get("POST /tfstate/v1/runner/history")).get("raw").asText())
                .isEqualTo("{\"serial\":3,\"lineage\":\"l\"}");
        verify(terrakubeClient, never()).createHistory(any(), any(), any());
    }

    @Test
    void planIsOnlyDownloadedFromTheJobOwnLocation() throws Exception {
        givenPlanUrl("https://attacker.example/steal");
        assertThat(state.downloadTerraformPlan("org", "ws", "7", "s2", workingDirectory)).isFalse();
        givenPlanUrl(apiUrl + "/tfstate/v1/organization/org/workspace/other/jobId/7/step/s1/terraform.tfstate");
        assertThat(state.downloadTerraformPlan("org", "ws", "7", "s2", workingDirectory)).isFalse();
        assertThat(calls).isEmpty();

        givenPlanUrl(apiUrl + "/tfstate/v1/organization/org/workspace/ws/jobId/7/step/s1/terraform.tfstate");
        assertThat(state.downloadTerraformPlan("org", "ws", "7", "s2", workingDirectory)).isTrue();
        assertThat(Files.readString(new File(workingDirectory, "terraformLibrary.tfPlan").toPath())).isEqualTo("PLAN");
    }

    @Test
    void missingArtifactsAreNoOps() {
        assertThat(state.saveTerraformPlan("org", "ws", "7", "s1", workingDirectory)).isNull();
        givenPlanUrl(null);
        assertThat(state.downloadTerraformPlan("org", "ws", "7", "s2", workingDirectory)).isFalse();
        givenPlanUrl(" ");
        assertThat(state.downloadTerraformPlan("org", "ws", "7", "s2", workingDirectory)).isFalse();
        state.saveStateJson(new TerraformJob(), null, null);
        assertThat(calls).isEmpty();
    }

    @Test
    void emptyPlansAreReportedAsMissing() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        givenPlanUrl(apiUrl + "/tfstate/v1/organization/org/workspace/ws/jobId/7/step/s1/terraform.tfstate");
        assertThat(state.downloadTerraformPlan("org", "ws", "7", "s2", workingDirectory)).isFalse();
    }

    private void givenPlanUrl(String url) {
        JobAttributes attributes = new JobAttributes();
        attributes.setTerraformPlan(url);
        Job job = new Job();
        job.setAttributes(attributes);
        ResponseWithInclude<Job, Step> response = new ResponseWithInclude<>();
        response.setData(job);
        when(terrakubeClient.getJobById("org", "7")).thenReturn(response);
    }
}
