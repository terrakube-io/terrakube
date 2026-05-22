package io.terrakube.api.plugin.storage.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import io.terrakube.api.plugin.security.state.StateService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.ArchiveRepository;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.history.archive.Archive;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequestMapping("/tfstate/v1")
public class TerraformStateController {

    private final StorageTypeService storageTypeService;
    private final ArchiveRepository archiveRepository;
    private final WorkspaceRepository workspaceRepository;
    private final HistoryRepository historyRepository;
    @SuppressWarnings("unused")
    @Autowired
    private StateService stateService;
    private final String hostname; 

    public TerraformStateController(StorageTypeService storageTypeService, 
                                    ArchiveRepository archiveRepository, 
                                    WorkspaceRepository workspaceRepository, 
                                    HistoryRepository historyRepository, 
                                    @Value("${io.terrakube.hostname}") String hostname) {
        this.storageTypeService = storageTypeService;
        this.archiveRepository = archiveRepository;
        this.workspaceRepository = workspaceRepository;
        this.historyRepository = historyRepository;
        this.hostname = hostname;  
    }
    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/jobId/{jobId}/step/{stepId}/terraform.tfstate", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public @ResponseBody byte[] getTerraformPlanBinary(@PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId, @PathVariable("jobId") String jobId,
            @PathVariable("stepId") String stepId) {
        return storageTypeService.getTerraformPlan(organizationId, workspaceId, jobId, stepId);
    }

    @PutMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/jobId/{jobId}/step/{stepId}/terraform.tfstate")
    public ResponseEntity<String> uploadTerraformPlanBinary(HttpServletRequest httpServletRequest,
                                                            @PathVariable("organizationId") String organizationId,
                                                            @PathVariable("workspaceId") String workspaceId,
                                                            @PathVariable("jobId") String jobId,
                                                            @PathVariable("stepId") String stepId) throws IOException {
        log.info("uploadTerraformPlanBinary org={} ws={} job={} step={}", organizationId, workspaceId, jobId, stepId);
        byte[] planBytes = IOUtils.toByteArray(httpServletRequest.getInputStream());
        ResponseEntity<String> mismatch = UploadIntegrity.verify(httpServletRequest, planBytes);
        if (mismatch != null) return mismatch;
        storageTypeService.uploadTerraformPlan(organizationId, workspaceId, jobId, stepId, planBytes);
        return ResponseEntity.status(HttpStatus.CREATED).body("");
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/history/{historyId}/terraform.tfstate",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public @ResponseBody byte[] getHistoryState(@PathVariable("organizationId") String organizationId,
                                                @PathVariable("workspaceId") String workspaceId,
                                                @PathVariable("historyId") String historyId) {
        // Read-back verification path. Mirrors the PUT URL; uses the storage backend's
        // raw-history key ({historyId}.raw.json) — same bytes that were just committed.
        return storageTypeService.getTerraformStateJson(organizationId, workspaceId, historyId + ".raw");
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/history/{historyId}/terraform.json.tfstate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody byte[] getHistoryStateJson(@PathVariable("organizationId") String organizationId,
                                                    @PathVariable("workspaceId") String workspaceId,
                                                    @PathVariable("historyId") String historyId) {
        return storageTypeService.getTerraformStateJson(organizationId, workspaceId, historyId);
    }

    @PutMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/history/{historyId}/terraform.tfstate")
    public ResponseEntity<String> uploadHistoryState(HttpServletRequest httpServletRequest,
                                                     @PathVariable("organizationId") String organizationId,
                                                     @PathVariable("workspaceId") String workspaceId,
                                                     @PathVariable("historyId") String historyId) throws IOException {
        log.info("uploadHistoryState org={} ws={} history={}", organizationId, workspaceId, historyId);
        byte[] bytes = IOUtils.toByteArray(httpServletRequest.getInputStream());
        ResponseEntity<String> mismatch = UploadIntegrity.verify(httpServletRequest, bytes);
        if (mismatch != null) return mismatch;
        storageTypeService.uploadState(organizationId, workspaceId, new String(bytes, StandardCharsets.UTF_8), historyId);
        return ResponseEntity.status(HttpStatus.CREATED).body("");
    }

    @PutMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/history/{historyId}/terraform.json.tfstate")
    public ResponseEntity<String> uploadHistoryStateJson(HttpServletRequest httpServletRequest,
                                                         @PathVariable("organizationId") String organizationId,
                                                         @PathVariable("workspaceId") String workspaceId,
                                                         @PathVariable("historyId") String historyId) throws IOException {
        log.info("uploadHistoryStateJson org={} ws={} history={}", organizationId, workspaceId, historyId);
        byte[] bytes = IOUtils.toByteArray(httpServletRequest.getInputStream());
        ResponseEntity<String> mismatch = UploadIntegrity.verify(httpServletRequest, bytes);
        if (mismatch != null) return mismatch;
        storageTypeService.uploadTerraformStateJson(organizationId, workspaceId, new String(bytes, StandardCharsets.UTF_8), historyId);
        return ResponseEntity.status(HttpStatus.CREATED).body("");
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/state/{stateFilename}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@stateService.hasManageStatePermission(authentication, #organizationId, #workspaceId)")
    public @ResponseBody byte[] getTerraformStateJson(@PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId, @PathVariable("stateFilename") String stateFilename) {
        return storageTypeService.getTerraformStateJson(organizationId, workspaceId, stateFilename);
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/state/terraform.tfstate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@stateService.hasManageStatePermission(authentication, #organizationId, #workspaceId)")
    public @ResponseBody byte[] getCurrentTerraformState(@PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId) {
        return storageTypeService.getCurrentTerraformState(organizationId, workspaceId);
    }

    @PutMapping(value = "/archive/{archiveId}/terraform.tfstate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> uploadHostedState(HttpServletRequest httpServletRequest,
            @PathVariable("archiveId") String archiveId) throws IOException {
        log.info("uploadHostedState for: {}", archiveId);
        Optional<Archive> archive = archiveRepository.findById(UUID.fromString(archiveId));
        if (archive.isPresent()) {
            Archive archiveData = archive.get();
            String terraformState = IOUtils.toString(httpServletRequest.getInputStream(),
                    StandardCharsets.UTF_8.name());
            log.debug(terraformState);
            storageTypeService.uploadState(
                    archiveData.getHistory().getWorkspace().getOrganization().getId().toString(),
                    archiveData.getHistory().getWorkspace().getId().toString(),
                    terraformState,
                    archiveData.getHistory().getId().toString());
            archiveRepository.deleteById(archiveData.getId());
            return ResponseEntity.status(201).body("");
        } else {
            return ResponseEntity.status(403).body("");
        }
    }

    @PutMapping(value = "/archive/{archiveId}/terraform.json.tfstate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> uploadJsonHostedState(HttpServletRequest httpServletRequest,
            @PathVariable("archiveId") String archiveId) throws IOException {
        log.info("uploadJsonHostedState for: {}", archiveId);
        Optional<Archive> archive = archiveRepository.findById(UUID.fromString(archiveId));
        if (archive.isPresent()) {
            Archive archiveData = archive.get();
            String terraformJsonState = IOUtils.toString(httpServletRequest.getInputStream(),
                    StandardCharsets.UTF_8.name());
            log.debug(terraformJsonState);
            storageTypeService.uploadTerraformStateJson(
                    archiveData.getHistory().getWorkspace().getOrganization().getId().toString(),
                    archiveData.getHistory().getWorkspace().getId().toString(),
                    terraformJsonState,
                    archiveData.getHistory().getId().toString());
            archiveRepository.deleteById(archiveData.getId());
            return ResponseEntity.status(201).body("");
        } else {
            return ResponseEntity.status(403).body("");
        }
    }

    @PutMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/rollback/{stateFilename}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@stateService.hasManageStatePermission(authentication, #organizationId, #workspaceId)")
    public ResponseEntity<String> rollbackToState(
            @PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId,
            @PathVariable("stateFilename") String stateFilename) {

        log.info("Rolling back workspace {} in organization {} to state {}", workspaceId, organizationId,
                stateFilename);

        try {
            // Retrieve the previous JSON state
            byte[] previousJsonState = storageTypeService.getTerraformStateJson(organizationId, workspaceId,
                    stateFilename);
            if (previousJsonState == null || previousJsonState.length == 0) {
                log.error("Failed to retrieve the JSON state: {}", stateFilename);
                return ResponseEntity.status(404).body("JSON state not found");
            }

            // Retrieve the previous raw Terraform state by replacing ".json" with
            // ".raw.json"
            String rawStateFilename = stateFilename + ".raw";
            byte[] previousRawState = storageTypeService.getTerraformStateJson(organizationId, workspaceId,
                    rawStateFilename);
            if (previousRawState == null || previousRawState.length == 0) {
                log.error("Failed to retrieve the raw Terraform state: {}", rawStateFilename);
                return ResponseEntity.status(404).body("Raw Terraform state not found");
            }

            // Create a new history entry for the rollback
            History newHistory = new History();
            newHistory.setWorkspace(workspaceRepository.findById(UUID.fromString(workspaceId)).orElse(null));
            newHistory.setSerial(1);
            newHistory.setMd5("0");
            newHistory.setLineage("0");
            newHistory.setOutput(""); // Output will be updated with the new state URL
            newHistory.setJobReference(stateFilename.replace(".json", "")); // Use the previous history id as the job reference
            historyRepository.save(newHistory);

            // Upload the previous JSON state as the current state
            String jsonStateContent = new String(previousJsonState, StandardCharsets.UTF_8);
            storageTypeService.uploadTerraformStateJson(
                    organizationId,
                    workspaceId,
                    jsonStateContent,
                    newHistory.getId().toString());

            // Upload the previous raw Terraform state as the current state
            String rawStateContent = new String(previousRawState, StandardCharsets.UTF_8);
            storageTypeService.uploadState(
                    organizationId,
                    workspaceId,
                    rawStateContent,
                    newHistory.getId().toString());

            // Update history output with new state URL
            newHistory.setOutput(String.format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                    hostname,
                    organizationId,
                    workspaceId,
                    newHistory.getId().toString()));
            historyRepository.save(newHistory);

            log.info("State rollback successful for workspace: {}", workspaceId);
            return ResponseEntity.status(201).body("Rollback successful");

        } catch (Exception e) {
            log.error("Error during rollback: {}", e.getMessage());
            return ResponseEntity.status(500).body("Rollback failed");
        }
    }

}
