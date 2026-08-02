package io.terrakube.api.plugin.storage.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.storage.StorageTypeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.terrakube.api.plugin.streaming.StreamingService;

import java.nio.charset.StandardCharsets;

@AllArgsConstructor
@RestController
@Slf4j
@RequestMapping("/tfoutput/v1")
public class TerraformOutputController {

    private StorageTypeService storageTypeService;

    private StreamingService streamingService;

    @Transactional
    @GetMapping(
            value = "/organization/{organizationId}/job/{jobId}/step/{stepId}",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public @ResponseBody byte[] getFile(@PathVariable("organizationId") String organizationId, @PathVariable("jobId") String jobId, @PathVariable("stepId") String stepId) {
        String tempLogs = streamingService.getCurrentLogs(stepId);
        
        if (tempLogs.length() > 0) {
            log.info("Reading output from redis stream....");
            return tempLogs.getBytes(StandardCharsets.UTF_8);
        } else {
            log.info("Reading output from storage");
            return storageTypeService.getStepOutput(organizationId, jobId, stepId);
        }

    }

    @GetMapping(
            value = "/organization/{organizationId}/job/{jobId}/step/{stepId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamOutput(@PathVariable("organizationId") String organizationId, @PathVariable("jobId") String jobId, @PathVariable("stepId") String stepId) {
        SseEmitter emitter = new SseEmitter(0L);
        streamingService.streamStepLogsAsync(stepId, emitter);
        return emitter;
    }
}
