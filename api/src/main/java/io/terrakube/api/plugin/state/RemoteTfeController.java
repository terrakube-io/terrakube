package io.terrakube.api.plugin.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.terrakube.api.plugin.state.model.apply.ApplyRunData;
import io.terrakube.api.plugin.state.model.configuration.ConfigurationData;
import io.terrakube.api.plugin.state.model.entitlement.EntitlementData;
import io.terrakube.api.plugin.state.model.organization.OrganizationData;
import io.terrakube.api.plugin.state.model.organization.capacity.OrgCapacityData;
import io.terrakube.api.plugin.state.model.outputs.StateOutputs;
import io.terrakube.api.plugin.state.model.plan.PlanRunData;
import io.terrakube.api.plugin.state.model.project.ProjectData;
import io.terrakube.api.plugin.state.model.project.ProjectList;
import io.terrakube.api.plugin.state.model.runs.RunsData;
import io.terrakube.api.plugin.state.model.runs.RunsDataList;
import io.terrakube.api.plugin.state.model.state.StateData;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceData;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceError;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceList;
import io.terrakube.api.plugin.state.model.workspace.state.consumers.StateConsumerList;
import io.terrakube.api.plugin.state.model.workspace.tags.TagDataList;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/remote/tfe/v2/")
@AllArgsConstructor
public class RemoteTfeController {

    RemoteTfeService remoteTfeService;

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "organizations/{organizationName}/entitlement-set")
    public ResponseEntity<EntitlementData> getOrgEntitlementSet(
            @PathVariable("organizationName") String organizationName, Principal principal) {
        return ResponseEntity.of(Optional.ofNullable(
                remoteTfeService.getOrgEntitlementSet(organizationName, ((JwtAuthenticationToken) principal))));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "organizations/{organizationName}/capacity")
    public ResponseEntity<OrgCapacityData> getOrgCapacity(@PathVariable("organizationName") String organizationName,
            Principal principal) {
        return ResponseEntity.of(Optional
                .ofNullable(remoteTfeService.getOrgCapacity(organizationName, ((JwtAuthenticationToken) principal))));
    }

    @GetMapping(produces = "application/vnd.api+json", path = "ping")
    public ResponseEntity<String> ping() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("TFP-API-Version", "2.5");
        responseHeaders.set("TFP-AppName", "Terrakube");
        ResponseEntity<String> response = new ResponseEntity<>(responseHeaders, HttpStatus.NOT_FOUND);
        return response;
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "organizations/{organizationName}")
    public ResponseEntity<OrganizationData> getOrgInformation(@PathVariable("organizationName") String organizationName,
            Principal principal) {
        return ResponseEntity.of(Optional
                .ofNullable(remoteTfeService.getOrgInformation(organizationName, (JwtAuthenticationToken) principal)));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "organizations/{organizationName}/workspaces/{workspaceName}")
    public ResponseEntity<WorkspaceData> getWorkspace(@PathVariable("organizationName") String organizationName,
            @PathVariable("workspaceName") String workspaceName, Principal principal) {
        log.info("Searching: {} {}", organizationName, workspaceName);
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.getWorkspace(organizationName, workspaceName,
                new HashMap<>(), (JwtAuthenticationToken) principal)));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "workspaces/{workspaceId}/relationships/remote-state-consumers")
    public ResponseEntity<StateConsumerList> getWorkspaceStateConsumers(@PathVariable("workspaceId") String workspaceId,
            Principal principal) {
        log.info("Searching Workspace Consumers for Id: {}", workspaceId);
        return ResponseEntity.of(Optional.ofNullable(
                remoteTfeService.getWorkspaceStateConsumers(workspaceId, (JwtAuthenticationToken) principal)));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "organizations/{organizationName}/workspaces")
    public ResponseEntity<WorkspaceList> listWorkspace(@PathVariable("organizationName") String organizationName,
            @RequestParam("search[tags]") Optional<String> searchTags,
            @RequestParam("search[name]") Optional<String> searchName, Principal principal) {
        log.info("Searching Tags: {} {}", organizationName, searchTags.isPresent() ? searchTags.get() : null);
        log.info("Searching Names: {} {}", organizationName, searchName.isPresent() ? searchName.get() : null);
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.listWorkspace(organizationName, searchTags,
                searchName, (JwtAuthenticationToken) principal)));
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/relationships/tags")
    public ResponseEntity<String> updateWorkspaceTags(@PathVariable("workspaceId") String workspaceId,
            @RequestBody TagDataList tagDataList) {
        log.info("Updating Workspace Tags {}", tagDataList.toString());
        boolean workspaceTags = remoteTfeService.updateWorkspaceTags(workspaceId, tagDataList);
        if (workspaceTags) {
            return ResponseEntity.status(204).body("");
        } else {
            return ResponseEntity.status(404).body("");
        }
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "organizations/{organizationName}/workspaces")
    public ResponseEntity<WorkspaceData> createWorkspace(@PathVariable("organizationName") String organizationName,
            @RequestBody WorkspaceData workspaceData, Principal principal) throws IOException {
        log.info("Create workspace with data: {}", workspaceData.toString());

        Optional<WorkspaceData> newWorkspace = Optional.ofNullable(
                remoteTfeService.createWorkspace(organizationName, workspaceData, (JwtAuthenticationToken) principal));
        if (newWorkspace.isPresent()) {
            log.info("Created: {}", newWorkspace.get().toString());
            return ResponseEntity.status(201).body(newWorkspace.get());
        } else {
            return ResponseEntity.status(403).body(new WorkspaceData());
        }
    }

    @Transactional
    @PatchMapping(produces = "application/vnd.api+json", path = "workspaces/{workspacesId}")
    public ResponseEntity<WorkspaceData> updateWorkspace(@PathVariable("workspacesId") String workspacesId,
            @RequestBody WorkspaceData workspaceData, Principal principal) {
        log.info("Create {}", workspaceData.toString());
        Optional<WorkspaceData> updatedWorkspace = Optional.ofNullable(
                remoteTfeService.updateWorkspace(workspacesId, workspaceData, (JwtAuthenticationToken) principal));
        if (updatedWorkspace.isPresent()) {
            log.info("Created: {}", updatedWorkspace.get().toString());
            return ResponseEntity.status(201).body(updatedWorkspace.get());
        } else {
            return ResponseEntity.status(500).body(new WorkspaceData());
        }
    }

    // Only used for local runs
    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/actions/lock")
    public ResponseEntity<WorkspaceData> lockWorkspace(@PathVariable("workspaceId") String workspaceId,
            Principal principal) {
        log.info("Lock {}", workspaceId);
        if (remoteTfeService.isWorkspaceLocked(workspaceId)) {
            WorkspaceData workspaceData = new WorkspaceData();
            workspaceData.setErrors(new ArrayList<WorkspaceError>());
            WorkspaceError workspaceError = new WorkspaceError();
            workspaceError.setStatus("409");
            workspaceError.setTitle("conflict");
            workspaceError.setDetail("Unable to lock workspace. The workspace is already locked.");
            workspaceData.getErrors().add(workspaceError);
            return ResponseEntity.status(409).body(workspaceData);
        } else {
            return ResponseEntity.of(Optional.ofNullable(
                    remoteTfeService.updateWorkspaceLock(workspaceId, true, (JwtAuthenticationToken) principal)));
        }
    }

    // Only used for local runs
    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/actions/unlock")
    public ResponseEntity<WorkspaceData> unlockWorkspace(@PathVariable("workspaceId") String workspaceId,
            Principal principal) {
        log.info("Unlock {}", workspaceId);
        return ResponseEntity.of(Optional.ofNullable(
                remoteTfeService.updateWorkspaceLock(workspaceId, false, (JwtAuthenticationToken) principal)));
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/state-versions")
    public ResponseEntity<StateData> createWorkspaceState(@PathVariable("workspaceId") String workspaceId,
            @RequestBody StateData stateData) {
        log.info("Create State /remote/tfe/v2/ {}", workspaceId);
        log.info("Body: {}", stateData.toString());
        return ResponseEntity.of(Optional.of(remoteTfeService.createWorkspaceState(workspaceId, stateData)));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/state-versions/{historyId}")
    public ResponseEntity<StateData> getWorkspaceState(@PathVariable("historyId") String historyId) {
        log.info("Looking history id {}", historyId);
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.getWorkspaceState(historyId)));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/current-state-version")
    public ResponseEntity<StateData> getCurrentWorkspaceState(@PathVariable("workspaceId") String workspaceId)
            throws JsonProcessingException {
        log.info("Get current workspace state {}", workspaceId);
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.getCurrentWorkspaceState(workspaceId)));
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/configuration-versions")
    public ResponseEntity<ConfigurationData> createConfigurationVersion(@PathVariable("workspaceId") String workspaceId,
            @RequestBody ConfigurationData configurationData) {
        log.info("Creating Configuration Version for worspaceId {}", workspaceId);
        return ResponseEntity.status(201)
                .body(remoteTfeService.createConfigurationVersion(workspaceId, configurationData));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/configuration-versions/{configurationId}")
    public ResponseEntity<ConfigurationData> getConfigurationVersion(
            @PathVariable("configurationId") String configurationId) {
        log.info("Searching Configuration Version Id {}", configurationId);
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.searchConfiguration(configurationId)));
    }

    @Transactional
    @PutMapping(path = "/configuration-versions/{configurationid}")
    public ResponseEntity<String> uploadConfiguration(HttpServletRequest httpServletRequest,
            @PathVariable("configurationid") String configurationId) throws IOException {
        log.info("Uploading Id {} file", configurationId);
        remoteTfeService.uploadFile(configurationId, httpServletRequest.getInputStream());
        log.info("File created");
        return ResponseEntity.ok().body("");
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/runs")
    public ResponseEntity<RunsData> createRun(@RequestBody RunsData runsData)
            throws SchedulerException, ParseException {
        log.info("Create new run");
        return ResponseEntity.status(201).body(remoteTfeService.createRun(runsData));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/runs")
    public ResponseEntity<RunsDataList> getWorkspaceRuns(@PathVariable("workspaceId") String workspaceId) {
        log.info("Get workspace runs for {}", workspaceId);
        return ResponseEntity.ok(remoteTfeService.getWorkspaceRuns(workspaceId));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/organizations/{organizationName}/runs/queue")
    public ResponseEntity<RunsDataList> getRunQueue(@PathVariable("organizationName") String organizationName) {
        return ResponseEntity.ok(remoteTfeService.getRunsQueue(organizationName));
    }

    @GetMapping(produces = "application/vnd.api+json", path = "/runs/{runsId}/run-events")
    public ResponseEntity<String> getRunEvents(@PathVariable("runsId") String runsId) {
        log.info("Gettinng Runs Events for Run Id {}", runsId);
        return ResponseEntity.status(200).body("{\n" +
                "  \"data\": []\n" +
                "}");
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/runs/{runId}")
    public ResponseEntity<RunsData> getRun(@PathVariable("runId") String runId,
            @RequestParam(name = "include", required = false) String include) {
        log.info("Get run {}", runId.replace("run-", ""));
        int runIdFixed = Integer.parseInt(runId.replace("run-", ""));
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.getRun(runIdFixed, include)));
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/runs/{runId}/actions/apply")
    public ResponseEntity<RunsData> runApply(@PathVariable("runId") String runId) {
        log.info("Applying run {}", runId.replace("run-", ""));
        int runIdFixed = Integer.parseInt(runId.replace("run-", ""));
        return ResponseEntity.ok(remoteTfeService.runApply(runIdFixed));
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/runs/{runId}/actions/discard")
    public ResponseEntity<RunsData> runDiscard(@PathVariable("runId") String runId) {
        log.info("Running discard: {}", runId.replace("run-", ""));
        int runIdFixed = Integer.parseInt(runId.replace("run-", ""));
        return ResponseEntity.ok(remoteTfeService.runDiscard(runIdFixed));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/plans/{planId}")
    public ResponseEntity<PlanRunData> getPlan(@PathVariable("planId") int planId) {
        return ResponseEntity.ok(remoteTfeService.getPlan(planId));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/applies/{applyId}")
    public ResponseEntity<ApplyRunData> getApply(@PathVariable("applyId") int applyId) {
        return ResponseEntity.ok(remoteTfeService.getApply(applyId));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/plans/logs/{planId1}/{planId2}")
    public ResponseEntity<String> getPlanLogs(@PathVariable("planId1") String planId1,@PathVariable("planId2") String planId2,
            @RequestParam int offset, @RequestParam int limit) throws IOException {
        log.info("Getting plan logs for planId: {}", planId1 + "/" + planId2);
        return ResponseEntity.of(Optional
                .ofNullable(new String(remoteTfeService.getPlanLogs(planId1 + "/" + planId2, offset, limit), StandardCharsets.UTF_8)));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/applies/logs/{applyId1}/{applyId2}")
    public ResponseEntity<String> getApplyLogs(@PathVariable("applyId1") String applyId1,@PathVariable("applyId2") String applyId2,
            @RequestParam int offset, @RequestParam int limit) throws IOException {
        log.info("Getting plan logs for applyId: {}", applyId1 + "/" + applyId2);
        return ResponseEntity
                .of(Optional.ofNullable(
                        new String(remoteTfeService.getApplyLogs(applyId1 + "/" + applyId2, offset, limit), StandardCharsets.UTF_8)));
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/workspaces/{workspaceId}/current-state-version-outputs")
    public ResponseEntity<StateOutputs> getCurrentOutputs(@PathVariable("workspaceId") String workspaceId) {
        log.info("Get current outputs for: {}", workspaceId);
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.getCurrentOutputs(workspaceId)));
    }

    @GetMapping(value = "configuration-versions/{planId}/terraformContent.tar.gz", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public @ResponseBody byte[] getTerraformPlanBinary(@PathVariable("planId") String planId) {
        return remoteTfeService.getContentFile(planId);
    }

    @Transactional
    @GetMapping(produces = "application/vnd.api+json", path = "/organizations/{organizationName}/projects")
    public ResponseEntity<ProjectList> searchOrgProjects(@PathVariable("organizationName") String organizationName, @RequestParam("filter[names]") Optional<String> filterNames, Principal principal) throws IOException {
        log.info("Searching projects: org: {} filter: {}", organizationName, filterNames.orElse(null));
        return ResponseEntity.of(Optional.ofNullable(remoteTfeService.searchOrganizationProjects(organizationName, ((JwtAuthenticationToken) principal))));
    }

    @Transactional
    @PostMapping(produces = "application/vnd.api+json", path = "/organizations/{organizationName}/projects")
    public ResponseEntity<ProjectData> createOrgProjects(@PathVariable("organizationName") String organizationName, @RequestBody ProjectData projectData , Principal principal) throws IOException {
        log.info("Creating project: org: {}  body: {}", organizationName, projectData);
        ProjectData projectDataResponse = remoteTfeService.createOrganizationProject(organizationName, projectData, ((JwtAuthenticationToken) principal));
        if (projectDataResponse != null) {
          return ResponseEntity.status(201).body(projectDataResponse);
        } else
          return ResponseEntity.status(403).body(null);
    }

}
