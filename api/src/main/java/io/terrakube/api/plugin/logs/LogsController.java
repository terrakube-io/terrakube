package io.terrakube.api.plugin.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<Void> setupConsumerGroups(@PathVariable("jobId") String jobId) {
        log.info("Setting up consumer groups for job {}", jobId);
        logsService.setupConsumerGroups(jobId);
        return ResponseEntity.ok().build();
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", value = "")
    public ResponseEntity<Void> appendLogs(@RequestBody LogsRequest logsRequest
    ) {
        if (!logsRequest.getData().isEmpty()) {
            log.info("Appending logs in Redis for job {}", logsRequest.getData().get(0).getJobId().toString());
        }
        logsService.appendLogs(logsRequest.getData());
        return ResponseEntity.ok().build();
    }
}