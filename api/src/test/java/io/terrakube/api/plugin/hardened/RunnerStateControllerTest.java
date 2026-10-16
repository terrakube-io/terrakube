package io.terrakube.api.plugin.hardened;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class RunnerStateControllerTest {

    StorageTypeService storage = mock(StorageTypeService.class);
    RedisTemplate redis = mock(RedisTemplate.class);
    ValueOperations values = mock(ValueOperations.class);
    RunnerJobGuard guard = mock(RunnerJobGuard.class);
    RunnerSourceService source = mock(RunnerSourceService.class);
    WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    HistoryRepository histories = mock(HistoryRepository.class);
    HardenedModeProperties properties = new HardenedModeProperties();
    RunnerStateController controller;
    UUID workspaceId = UUID.randomUUID();
    String stepId = UUID.randomUUID().toString();
    Jwt jwt;

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
        controller = new RunnerStateController(storage, redis, properties, guard, source, workspaces, histories, "api.example");
        jwt = runner("7", stepId);
        when(guard.jobActive(any())).thenReturn(true);
        when(guard.stepRunning(any())).thenReturn(true);
        when(guard.stepStartingOrRunning(any())).thenReturn(true);
        when(guard.canWriteState(any())).thenReturn(true);
    }

    @Test
    void stateIsReadFromTheTokenWorkspace() {
        when(storage.readCurrentState("org", workspaceId.toString())).thenReturn(Optional.of("{}".getBytes()));
        assertThat(controller.getState(jwt).getStatusCode()).isEqualTo(HttpStatus.OK);
        when(storage.readCurrentState("org", workspaceId.toString())).thenReturn(Optional.empty());
        assertThat(controller.getState(jwt).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(storage.readCurrentState("org", workspaceId.toString())).thenReturn(Optional.of(new byte[0]));
        assertThat(controller.getState(jwt).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(storage.readCurrentState("org", workspaceId.toString())).thenThrow(new IllegalStateException("s3 down"));
        assertThatThrownBy(() -> controller.getState(jwt)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stateWritesNeedAStateWritingRunningStep() {
        assertThat(controller.updateState(jwt, null, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(storage).uploadCurrentState(eq("org"), eq(workspaceId.toString()), any());

        when(guard.canWriteState(any())).thenReturn(false);
        assertThat(controller.updateState(jwt, null, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.createHistory(jwt, history("{}", "{}")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(storage, times(1)).uploadCurrentState(any(), any(), any());
    }

    @Test
    void lockIsExclusiveWhileTheHolderRuns() throws Exception {
        when(values.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
        assertThat(controller.lock(jwt, "{\"ID\":\"a\"}").getStatusCode()).isEqualTo(HttpStatus.OK);

        String held = lock("a", "7", stepId);
        when(values.get(anyString())).thenReturn(held);
        when(values.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);
        when(guard.isStepRunning("7", stepId)).thenReturn(true);

        assertThat(controller.lock(jwt, "{\"ID\":\"b\"}").getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(controller.updateState(jwt, "b", new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(controller.updateState(jwt, "a", new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.unlock(jwt, "{\"ID\":\"b\"}").getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(controller.unlock(jwt, "{\"ID\":\"a\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(redis).delete(RunnerStateController.lockKey(workspaceId.toString()));
    }

    @Test
    void staleLocksAreTakenOverAndReleased() throws Exception {
        when(values.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);
        when(values.get(anyString())).thenReturn(lock("old", "3", UUID.randomUUID().toString()));
        when(guard.isStepRunning(anyString(), anyString())).thenReturn(false);

        assertThat(controller.lock(jwt, "{\"ID\":\"new\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(values).set(eq(RunnerStateController.lockKey(workspaceId.toString())), contains("\"id\":\"new\""), any(Duration.class));
        assertThat(controller.updateState(jwt, "anything", new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.unlock(jwt, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        when(values.get(anyString())).thenReturn("not json");
        assertThat(controller.lock(jwt, "{\"ID\":\"x\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        when(values.get(anyString())).thenReturn(null);
        assertThat(controller.lock(jwt, "{\"ID\":\"y\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.unlock(jwt, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void lockRequestsAreValidated() {
        assertThat(controller.lock(jwt, "not json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.lock(jwt, "{\"Who\":\"x\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.lock(jwt, "{\"ID\":null}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.lock(jwt, " ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        when(guard.stepRunning(any())).thenReturn(false);
        assertThat(controller.lock(jwt, "{\"ID\":\"a\"}").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        when(guard.jobActive(any())).thenReturn(false);
        assertThat(controller.unlock(jwt, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void historyIsCreatedServerSide() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(workspace));
        UUID historyId = UUID.randomUUID();
        when(histories.save(any(History.class))).thenAnswer(invocation -> {
            History history = invocation.getArgument(0);
            history.setId(historyId);
            return history;
        });

        String raw = "{\"serial\":4,\"lineage\":\"abc\"}";
        assertThat(controller.createHistory(jwt, history("{\"values\":{}}", raw)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<History> saved = ArgumentCaptor.forClass(History.class);
        verify(histories, times(2)).save(saved.capture());
        History history = saved.getValue();
        assertThat(history.getOutput()).isEqualTo("https://api.example/tfstate/v1/organization/org/workspace/" + workspaceId + "/state/" + historyId + ".json");
        assertThat(history.getSerial()).isEqualTo(4);
        assertThat(history.getLineage()).isEqualTo("abc");
        assertThat(history.getJobReference()).isEqualTo("7");
        verify(storage).uploadStateHistory(eq("org"), eq(workspaceId.toString()), eq(historyId.toString()), any(), any());
        verify(storage, never()).uploadCurrentState(any(), any(), any());
    }

    @Test
    void historyRequestsAreValidated() throws Exception {
        assertThat(controller.createHistory(jwt, "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.createHistory(jwt, "not json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.createHistory(jwt, "{\"json\":1,\"raw\":\"{}\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.createHistory(jwt, "{\"json\":\"{}\",\"raw\":2}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.createHistory(jwt, history("{}", "{}")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(workspace));
        assertThat(controller.createHistory(jwt, history("{}", "not json")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadsAndHeartbeatFollowTheStepLifecycle() {
        assertThat(controller.uploadPlan(jwt, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(storage).uploadTerraformPlan(eq("org"), eq(workspaceId.toString()), eq("7"), eq(stepId), any());
        assertThat(controller.uploadOutput(jwt, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(storage).uploadStepOutput(eq("org"), eq("7"), eq(stepId), any());
        assertThat(controller.heartbeat(jwt).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(values).set(eq("executor-job-heartbeat:7"), eq("1"), any(Duration.class));
        assertThat(controller.clearHeartbeat(jwt).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(redis).delete("executor-job-heartbeat:7");

        when(guard.stepRunning(any())).thenReturn(false);
        when(guard.stepStartingOrRunning(any())).thenReturn(false);
        when(guard.jobActive(any())).thenReturn(false);
        assertThat(controller.uploadPlan(jwt, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.uploadOutput(jwt, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.heartbeat(jwt).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.clearHeartbeat(jwt).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.getState(jwt).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.getConfiguration(jwt).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aStepThatHasNotStartedCanStillReportItsOutput() {
        when(guard.stepRunning(any())).thenReturn(false);
        assertThat(controller.uploadOutput(jwt, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.uploadPlan(jwt, new byte[]{1}).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void configurationIsServedForTheTokenJob() {
        when(source.content("7")).thenReturn(new byte[]{1, 2});
        assertThat(controller.getConfiguration(jwt).getBody()).containsExactly(1, 2);
        when(source.content("7")).thenReturn(new byte[0]);
        assertThat(controller.getConfiguration(jwt).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(source.content("7")).thenReturn(null);
        assertThat(controller.getConfiguration(jwt).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonRunnerPrincipalsAreRejected() {
        Jwt user = Jwt.withTokenValue("t").header("alg", "RS256").claim("iss", "https://dex").build();
        assertThatThrownBy(() -> controller.getState(user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.getState(null)).isInstanceOf(AccessDeniedException.class);
    }

    private String history(String json, String raw) {
        return new ObjectMapper().createObjectNode().put("json", json).put("raw", raw).toString();
    }

    private String lock(String id, String jobId, String step) {
        return new ObjectMapper().createObjectNode().put("id", id).put("jobId", jobId).put("stepId", step)
                .put("info", "{\"ID\":\"" + id + "\"}").toString();
    }

    private Jwt runner(String jobId, String step) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", RunnerToken.ISSUER)
                .claim(RunnerToken.CLAIM_ORGANIZATION_ID, "org")
                .claim(RunnerToken.CLAIM_WORKSPACE_ID, workspaceId.toString())
                .claim(RunnerToken.CLAIM_JOB_ID, jobId)
                .claim(RunnerToken.CLAIM_STEP_ID, step)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
