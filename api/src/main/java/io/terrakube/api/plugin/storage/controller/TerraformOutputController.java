package io.terrakube.api.plugin.storage.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.storage.StorageTypeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.plugin.streaming.StreamingService;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@Slf4j
@RequestMapping("/tfoutput/v1")
public class TerraformOutputController {

    private final StorageTypeService storageTypeService;

    private final StreamingService streamingService;

    private final boolean requireIntegrityHeader;

    public TerraformOutputController(StorageTypeService storageTypeService,
                                     StreamingService streamingService,
                                     @Value("${io.terrakube.api.upload.require-integrity-header:false}") boolean requireIntegrityHeader) {
        this.storageTypeService = storageTypeService;
        this.streamingService = streamingService;
        this.requireIntegrityHeader = requireIntegrityHeader;
    }

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
    @PreAuthorize("@stateService.hasManageStatePermissionByJob(authentication, #organizationId, #jobId)")
    public ResponseEntity<String> uploadStepOutput(HttpServletRequest httpServletRequest,
                                                   @PathVariable("organizationId") String organizationId,
                                                   @PathVariable("jobId") String jobId,
                                                   @PathVariable("stepId") String stepId) throws IOException {
        log.info("uploadStepOutput org={} job={} step={}", organizationId, jobId, stepId);
        byte[] output = IOUtils.toByteArray(httpServletRequest.getInputStream());
        ResponseEntity<String> mismatch = UploadIntegrity.verify(httpServletRequest, output, requireIntegrityHeader);
        if (mismatch != null) return mismatch;
        storageTypeService.uploadStepOutput(organizationId, jobId, stepId, output);
        return ResponseEntity.status(HttpStatus.CREATED).body("");
    }
}
