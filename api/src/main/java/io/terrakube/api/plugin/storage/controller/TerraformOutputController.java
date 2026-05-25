package io.terrakube.api.plugin.storage.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.storage.StorageTypeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.plugin.streaming.StreamingService;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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

    @PutMapping(
            value = "/organization/{organizationId}/job/{jobId}/step/{stepId}"
    )
    public ResponseEntity<String> uploadStepOutput(HttpServletRequest httpServletRequest,
                                                   @PathVariable("organizationId") String organizationId,
                                                   @PathVariable("jobId") String jobId,
                                                   @PathVariable("stepId") String stepId) throws IOException {
        log.info("uploadStepOutput org={} job={} step={}", organizationId, jobId, stepId);
        byte[] output = IOUtils.toByteArray(httpServletRequest.getInputStream());
        ResponseEntity<String> mismatch = UploadIntegrity.verify(httpServletRequest, output);
        if (mismatch != null) return mismatch;
        storageTypeService.uploadStepOutput(organizationId, jobId, stepId, output);
        return ResponseEntity.status(HttpStatus.CREATED).body("");
    }
}
