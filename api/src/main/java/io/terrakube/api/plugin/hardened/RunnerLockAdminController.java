package io.terrakube.api.plugin.hardened;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@ConditionalOnProperty(prefix = "io.terrakube.hardened", name = "enabled", havingValue = "true")
@RequestMapping("/tfstate/v1/lock")
public class RunnerLockAdminController {

    private final RedisTemplate<String, Object> redisTemplate;

    @SuppressWarnings("unchecked")
    public RunnerLockAdminController(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @DeleteMapping("/organization/{organizationId}/workspace/{workspaceId}")
    @PreAuthorize("!@stateService.isRunner(authentication) and @stateService.hasManageStatePermission(authentication, #organizationId, #workspaceId)")
    public ResponseEntity<Void> forceUnlock(@PathVariable("organizationId") String organizationId,
                                            @PathVariable("workspaceId") String workspaceId) {
        UUID.fromString(workspaceId);
        redisTemplate.delete(RunnerStateController.lockKey(workspaceId));
        log.warn("State lock of workspace {} was force-unlocked", workspaceId);
        return ResponseEntity.noContent().build();
    }
}
