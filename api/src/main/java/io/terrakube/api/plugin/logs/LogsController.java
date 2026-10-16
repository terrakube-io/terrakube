package io.terrakube.api.plugin.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import io.terrakube.api.plugin.security.token.RunnerToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.plugin.state.model.logs.LogsRequest;

@AllArgsConstructor
@RestController
@Slf4j
@RequestMapping("/logs")
public class LogsController {
    private final LogsService logsService;

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", value = "/{jobId}/setup-consumer-groups")
    public ResponseEntity<Void> setupConsumerGroups(@PathVariable("jobId") String jobId, Authentication authentication) {
        if (!runnerMayWrite(authentication, jobId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        log.info("Setting up consumer groups for job {}", jobId);
        logsService.setupConsumerGroups(jobId);
        return ResponseEntity.ok().build();
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", value = "")
    public ResponseEntity<Void> appendLogs(@RequestBody LogsRequest logsRequest, Authentication authentication
    ) {
        if (logsRequest.getData().stream().anyMatch(entry -> !runnerMayWrite(authentication, String.valueOf(entry.getJobId())))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!logsRequest.getData().isEmpty()) {
            log.info("Appending logs in Redis for job {}", logsRequest.getData().get(0).getJobId().toString());
        }
        logsService.appendLogs(logsRequest.getData());
        return ResponseEntity.ok().build();
    }

    private boolean runnerMayWrite(Authentication authentication, String jobId) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !RunnerToken.isRunner(jwt.getTokenAttributes())) {
            return true;
        }
        return jobId.equals(RunnerToken.claim(jwt.getTokenAttributes(), RunnerToken.CLAIM_JOB_ID));
    }
}
