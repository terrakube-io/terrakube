package io.terrakube.api.plugin.storage.controller;

import io.terrakube.api.plugin.logs.StepLogResponses;
import io.terrakube.api.plugin.logs.StepLogService;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import io.terrakube.api.plugin.streaming.StreamingService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@AllArgsConstructor
@RestController
@Slf4j
@RequestMapping("/tfoutput/v1")
public class TerraformOutputController {

    private final StepLogService stepLogService;

    private final StreamingService streamingService;

    @GetMapping(
            value = "/organization/{organizationId}/job/{jobId}/step/{stepId}",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<?> getFile(
            @PathVariable("organizationId") String organizationId,
            @PathVariable("jobId") String jobId,
            @PathVariable("stepId") String stepId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        String liveLogs = streamingService.getCurrentLogs(stepId, "");
        if (!liveLogs.isEmpty()) {
            return StepLogResponses.liveLogs(liveLogs);
        }

        StepLogService.StepLog stepLog = stepLogService.resolve(organizationId, jobId, stepId);
        if (!stepLog.isExists()) {
            return StepLogResponses.notFound();
        }

        if (stepLog.getBody() != null) {
            return StepLogResponses.cachedBody(stepLog.getBody(), rangeHeader);
        }

        // Large object: stream straight from storage. No buffering, no DB transaction held.
        ByteRange range = ByteRange.parse(rangeHeader).orElse(null);
        StepOutputStream out = stepLogService.openStream(organizationId, jobId, stepId, range);
        if (!out.isExists()) {
            return StepLogResponses.notFound();
        }
        return StepLogResponses.streamed(out);
    }

    @GetMapping(
            value = "/organization/{organizationId}/job/{jobId}/step/{stepId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamOutput(
            @PathVariable("organizationId") String organizationId,
            @PathVariable("jobId") String jobId,
            @PathVariable("stepId") String stepId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        streamingService.streamStepLogsAsync(stepId, emitter, parseResumeId(lastEventId), "");
        return emitter;
    }

    private RecordId parseResumeId(String lastEventId) {
        if (!StringUtils.hasText(lastEventId)) {
            return RecordId.of("0-0");
        }
        try {
            return RecordId.of(lastEventId);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unparseable Last-Event-ID '{}': {}", lastEventId, e.getMessage());
            return RecordId.of("0-0");
        }
    }
}
