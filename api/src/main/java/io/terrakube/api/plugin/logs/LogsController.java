package io.terrakube.api.plugin.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.plugin.state.model.logs.LogsRequest;

@AllArgsConstructor
@RestController
@Slf4j
@RequestMapping("/logs")
public class LogsController {
    private final LogsService logsService;
    private final LogsProperties properties;

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
        if (logsRequest == null || logsRequest.getData() == null || logsRequest.getData().isEmpty()) {
            return ResponseEntity.ok().build();
        }
        // Every line counts its text and a fixed overhead, so a request of thousands of empty lines
        // cannot slip under the limit and turn into that many Redis writes.
        long bytes = logsRequest.getData().stream()
                .mapToLong(line -> LogLine.LINE_OVERHEAD_BYTES + LogLine.bytesOf(line.getOutput()))
                .sum();
        if (properties.getAppendMaxBytes() > 0 && bytes > properties.getAppendMaxBytes()) {
            log.warn("Refusing a log request of {} bytes, the limit is {}", bytes, properties.getAppendMaxBytes());
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The log request is larger than " + properties.getAppendMaxBytes() + " bytes");
        }
        log.info("Appending logs in Redis for job {}", logsRequest.getData().get(0).getJobId());
        logsService.appendLogs(logsRequest.getData());
        return ResponseEntity.ok().build();
    }
}