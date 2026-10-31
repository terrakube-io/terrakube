package io.terrakube.api.plugin.storage.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
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
    @PreAuthorize("@stateService.hasManageStatePermission(authentication, #organizationId, #workspaceId)")
    public @ResponseBody byte[] getTerraformPlanBinary(@PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId, @PathVariable("jobId") String jobId,
            @PathVariable("stepId") String stepId) {
        return storageTypeService.getTerraformPlan(organizationId, workspaceId, jobId, stepId);
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/state/{stateFilename}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@stateService.hasReadStatePermission(authentication, #organizationId, #workspaceId)")
    public @ResponseBody byte[] getTerraformStateJson(@PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId, @PathVariable("stateFilename") String stateFilename) {
        return storageTypeService.getTerraformStateJson(organizationId, workspaceId, stateFilename);
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/state/terraform.tfstate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@stateService.hasReadStatePermission(authentication, #organizationId, #workspaceId)")
    public @ResponseBody byte[] getCurrentTerraformState(@PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId) {
        return storageTypeService.getCurrentTerraformState(organizationId, workspaceId);
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/backend/terraform.tfstate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> getHttpBackendState(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId) {
        StateService.HttpBackendAccessDecision decision = stateService.authorizeHttpBackendAccess(authorizationHeader, organizationId, workspaceId);
        if (decision == StateService.HttpBackendAccessDecision.UNAUTHORIZED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (decision != StateService.HttpBackendAccessDecision.ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        byte[] currentState = storageTypeService.getCurrentTerraformState(organizationId, workspaceId);
        if (currentState == null || currentState.length == 0) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(currentState);
    }

    @RequestMapping(
            value = "/organization/{organizationId}/workspace/{workspaceId}/backend/terraform.tfstate",
            method = {RequestMethod.POST, RequestMethod.PUT}
    )
    public ResponseEntity<Void> updateHttpBackendState(
            HttpServletRequest httpServletRequest,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "Content-MD5", required = false) String contentMd5,
            @PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId) throws IOException {
        StateService.HttpBackendAccessDecision decision = stateService.authorizeHttpBackendAccess(authorizationHeader, organizationId, workspaceId);
        if (decision == StateService.HttpBackendAccessDecision.UNAUTHORIZED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (decision != StateService.HttpBackendAccessDecision.ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        byte[] stateBytes = IOUtils.toByteArray(httpServletRequest.getInputStream());
        if (contentMd5 != null && !contentMd5.isBlank() && !matchesMd5(stateBytes, contentMd5.trim())) {
            log.warn("HTTP backend state upload Content-MD5 mismatch for org={} workspace={}", organizationId, workspaceId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        storageTypeService.saveCurrentTerraformState(organizationId, workspaceId, stateBytes);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/backend/terraform.tfstate")
    public ResponseEntity<Void> deleteHttpBackendState(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId) {
        StateService.HttpBackendAccessDecision decision = stateService.authorizeHttpBackendAccess(authorizationHeader, organizationId, workspaceId);
        if (decision == StateService.HttpBackendAccessDecision.UNAUTHORIZED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (decision != StateService.HttpBackendAccessDecision.ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        storageTypeService.deleteCurrentTerraformState(organizationId, workspaceId);
        return ResponseEntity.ok().build();
    }

    private static boolean matchesMd5(byte[] data, String expectedBase64Md5) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            String computed = Base64.getEncoder().encodeToString(digest);
            return computed.equals(expectedBase64Md5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
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
