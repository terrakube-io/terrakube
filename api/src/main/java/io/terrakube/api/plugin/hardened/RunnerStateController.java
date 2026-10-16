package io.terrakube.api.plugin.hardened;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@ConditionalOnProperty(prefix = "io.terrakube.hardened", name = "enabled", havingValue = "true")
@RequestMapping("/tfstate/v1/runner")
@PreAuthorize("@stateService.isRunner(authentication)")
public class RunnerStateController {

    static final String LOCK_KEY = "terrakube:runner-state-lock:%s";
    private static final String HEARTBEAT_KEY = "executor-job-heartbeat:";
    private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(45);

    private final StorageTypeService storageTypeService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final HardenedModeProperties properties;
    private final RunnerJobGuard guard;
    private final RunnerSourceService runnerSourceService;
    private final WorkspaceRepository workspaceRepository;
    private final HistoryRepository historyRepository;
    private final String hostname;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public RunnerStateController(StorageTypeService storageTypeService, RedisTemplate redisTemplate,
                                 HardenedModeProperties properties, RunnerJobGuard guard, RunnerSourceService runnerSourceService,
                                 WorkspaceRepository workspaceRepository, HistoryRepository historyRepository,
                                 @Value("${io.terrakube.hostname}") String hostname) {
        this.storageTypeService = storageTypeService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.guard = guard;
        this.runnerSourceService = runnerSourceService;
        this.workspaceRepository = workspaceRepository;
        this.historyRepository = historyRepository;
        this.hostname = hostname;
    }

    @GetMapping(value = "/state", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getState(@AuthenticationPrincipal Jwt jwt) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.jobActive(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<byte[]> state = storageTypeService.readCurrentState(scope.organizationId(), scope.workspaceId());
        return state.filter(bytes -> bytes.length > 0)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/configuration", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getConfiguration(@AuthenticationPrincipal Jwt jwt) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.jobActive(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] content = runnerSourceService.content(scope.jobId());
        return content == null || content.length == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(content);
    }

    @PostMapping(value = "/state")
    public ResponseEntity<String> updateState(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(value = "ID", required = false) String lockId,
                                              @RequestBody byte[] state) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.canWriteState(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<RunnerLock> lock = currentLock(scope.workspaceId());
        if (lock.isPresent() && lock.get().active(guard) && !lock.get().id().equals(lockId)) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(lock.get().info());
        }
        storageTypeService.uploadCurrentState(scope.organizationId(), scope.workspaceId(), state);
        log.info("Runner job {} step {} updated the state of workspace {}", scope.jobId(), scope.stepId(), scope.workspaceId());
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/lock", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> lock(@AuthenticationPrincipal Jwt jwt, @RequestBody String lockInfo) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.stepRunning(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String lockId = RunnerLock.idOf(objectMapper, lockInfo);
        if (lockId.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        RunnerLock requested = new RunnerLock(lockId, scope.jobId(), scope.stepId(), lockInfo);
        Duration ttl = Duration.ofMinutes(properties.getRunnerTokenTtlMinutes());
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey(scope.workspaceId()), requested.toJson(objectMapper), ttl))) {
            return ResponseEntity.ok().build();
        }
        Optional<RunnerLock> holder = currentLock(scope.workspaceId());
        if (holder.isEmpty() || !holder.get().active(guard)) {
            redisTemplate.opsForValue().set(lockKey(scope.workspaceId()), requested.toJson(objectMapper), ttl);
            log.warn("Runner job {} took over a stale state lock on workspace {}", scope.jobId(), scope.workspaceId());
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.LOCKED).body(holder.get().info());
    }

    @DeleteMapping(value = "/lock", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> unlock(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) String lockInfo) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.jobActive(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<RunnerLock> holder = currentLock(scope.workspaceId());
        if (holder.isEmpty()) {
            return ResponseEntity.ok().build();
        }
        if (holder.get().active(guard) && !holder.get().id().equals(RunnerLock.idOf(objectMapper, lockInfo))) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(holder.get().info());
        }
        redisTemplate.delete(lockKey(scope.workspaceId()));
        return ResponseEntity.ok().build();
    }

    @PutMapping(value = "/plan", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> uploadPlan(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] plan) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.stepRunning(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        storageTypeService.uploadTerraformPlan(scope.organizationId(), scope.workspaceId(), scope.jobId(), scope.stepId(), plan);
        return ResponseEntity.ok().build();
    }

    @PutMapping(value = "/output", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> uploadOutput(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] output) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.stepStartingOrRunning(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        storageTypeService.uploadStepOutput(scope.organizationId(), scope.jobId(), scope.stepId(), output);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/history", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> createHistory(@AuthenticationPrincipal Jwt jwt, @RequestBody String request) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.canWriteState(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(request);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        JsonNode stateJson = body.get("json");
        JsonNode rawState = body.get("raw");
        if (stateJson == null || !stateJson.isTextual() || rawState == null || !rawState.isTextual()) {
            return ResponseEntity.badRequest().build();
        }
        Optional<Workspace> workspace = workspaceRepository.findById(UUID.fromString(scope.workspaceId()));
        if (workspace.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String raw = rawState.asText();
        History history = new History();
        history.setWorkspace(workspace.get());
        history.setJobReference(scope.jobId());
        history.setOutput("");
        history.setMd5(DigestUtils.md5Hex(raw));
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            history.setSerial(parsed.path("serial").asInt(1));
            history.setLineage(parsed.path("lineage").asText("0"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        history = historyRepository.save(history);
        String historyId = history.getId().toString();
        storageTypeService.uploadStateHistory(scope.organizationId(), scope.workspaceId(), historyId,
                stateJson.asText().getBytes(StandardCharsets.UTF_8), raw.getBytes(StandardCharsets.UTF_8));
        history.setOutput(String.format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                hostname, scope.organizationId(), scope.workspaceId(), historyId));
        historyRepository.save(history);
        return ResponseEntity.ok().build();
    }

    @PutMapping(value = "/heartbeat", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> heartbeat(@AuthenticationPrincipal Jwt jwt) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.stepStartingOrRunning(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        redisTemplate.opsForValue().set(HEARTBEAT_KEY + scope.jobId(), "1", HEARTBEAT_TTL);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping(value = "/heartbeat")
    public ResponseEntity<Void> clearHeartbeat(@AuthenticationPrincipal Jwt jwt) {
        RunnerScope scope = RunnerScope.of(jwt);
        if (!guard.jobActive(jwt.getClaims())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        redisTemplate.delete(HEARTBEAT_KEY + scope.jobId());
        return ResponseEntity.ok().build();
    }

    static String lockKey(String workspaceId) {
        return String.format(LOCK_KEY, workspaceId);
    }

    private Optional<RunnerLock> currentLock(String workspaceId) {
        Object value = redisTemplate.opsForValue().get(lockKey(workspaceId));
        return value == null ? Optional.empty() : RunnerLock.parse(objectMapper, value.toString());
    }

    record RunnerLock(String id, String jobId, String stepId, String info) {

        boolean active(RunnerJobGuard guard) {
            return guard.isStepRunning(jobId, stepId);
        }

        String toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", id);
            node.put("jobId", jobId);
            node.put("stepId", stepId);
            node.put("info", info);
            return node.toString();
        }

        static Optional<RunnerLock> parse(ObjectMapper mapper, String value) {
            try {
                JsonNode node = mapper.readTree(value);
                return Optional.of(new RunnerLock(node.path("id").asText(""), node.path("jobId").asText(""),
                        node.path("stepId").asText(""), node.path("info").asText("")));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        static String idOf(ObjectMapper mapper, String lockInfo) {
            if (lockInfo == null || lockInfo.isBlank()) {
                return "";
            }
            try {
                JsonNode id = mapper.readTree(lockInfo).get("ID");
                return id == null || id.isNull() ? "" : id.asText();
            } catch (IOException e) {
                return "";
            }
        }
    }
}
