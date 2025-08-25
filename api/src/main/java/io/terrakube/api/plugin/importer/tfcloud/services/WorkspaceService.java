package io.terrakube.api.plugin.importer.tfcloud.services;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClient;
import io.terrakube.api.plugin.importer.tfcloud.StateVersion;
import io.terrakube.api.plugin.importer.tfcloud.TagResponse;
import io.terrakube.api.plugin.importer.tfcloud.TagResponse.TagData;
import io.terrakube.api.plugin.importer.tfcloud.TagResponse.TagData.TagAttributes;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse.VariableData;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse.VariableData.VariableAttributes;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImport;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImportRequest;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceListResponse;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;


import io.terrakube.api.rs.tag.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class WorkspaceService {

    private final WebClient webClient;
    WorkspaceRepository workspaceRepository;
    HistoryRepository historyRepository;
    VcsRepository vcsRepository;
    OrganizationRepository organizationRepository;
    VariableRepository variableRepository;
    WorkspaceTagRepository workspaceTagRepository;
    TagRepository tagRepository;

    private StorageTypeService storageTypeService;
    private String hostname;

    public WorkspaceService(@Value("${io.terrakube.hostname}") String hostname,
            WorkspaceRepository workspaceRepository,
            HistoryRepository historyRepository,
            StorageTypeService storageTypeService,
            VcsRepository vcsRepository,
            OrganizationRepository organizationRepository,
            VariableRepository variableRepositor,
            WorkspaceTagRepository workspaceTagRepository,
            TagRepository tagRepository,
            WebClient webClient) {
        this.webClient = webClient;
        this.workspaceRepository = workspaceRepository;
        this.historyRepository = historyRepository;
        this.storageTypeService = storageTypeService;
        this.vcsRepository = vcsRepository;
        this.organizationRepository = organizationRepository;
        this.variableRepository = variableRepositor;
        this.hostname = hostname;
        this.workspaceTagRepository = workspaceTagRepository;
        this.tagRepository = tagRepository;
    }

    public class NullResponseException extends RuntimeException {
        public NullResponseException(String message) {
            super(message);
        }
    }

    private <T> T makeRequest(String apiToken, String url, Class<T> responseType) {
        ResponseEntity<T> response = webClient
                .get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.api+json")
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.createException().flatMap(Mono::error))
                .toEntity(responseType)
                .block();
        if (response == null || response.getBody() == null) {
            throw new NullResponseException("Error: Response is null");
        }
        return response.getBody();
    }

    public List<WorkspaceImport.WorkspaceData> getWorkspaces(String apiToken, String apiUrl, String organization) {
        List<WorkspaceImport.WorkspaceData> allData = new ArrayList<>();
        int currentPage = 1;

        while (true) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl)
                    .pathSegment("organizations")
                    .pathSegment(organization)
                    .pathSegment("workspaces");

            String url = builder.toUriString() + "?page[size]=50&page[number]=" + currentPage;
            log.info("url: {}", url);
            WorkspaceListResponse response = makeRequest(apiToken, url, WorkspaceListResponse.class);

            if (response == null || response.getData() == null) {
                break;
            } else {
                allData.addAll(response.getData());
            }

            if (response.getMeta().getPagination().getNextPage() == null) {
                break;
            }

            currentPage++;
        }

        return allData;
    }

    public List<VariableAttributes> getVariables(String apiToken, String apiUrl, String organizationName,
            String workspaceName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl)
                .pathSegment("workspaces")
                .pathSegment(workspaceName)
                .pathSegment("vars");

        String url = builder.toUriString();
        VariableResponse response = makeRequest(apiToken, url, VariableResponse.class);
        if (response != null) {
            return response.getData().stream()
                    .map(VariableData::getAttributes)
                    .toList();
        } else {
            return Collections.emptyList();
        }
    }

    public StateVersion.Attributes getCurrentState(String apiToken, String apiUrl, String workspaceId) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl)
                    .pathSegment("workspaces")
                    .pathSegment(workspaceId)
                    .pathSegment("current-state-version");

            String url = builder.toUriString();
            StateVersion stateVersionResponse = makeRequest(apiToken, url, StateVersion.class);
            if (stateVersionResponse != null && stateVersionResponse.getData() != null) {
                return stateVersionResponse.getData().getAttributes();
            } else {
                throw new NullResponseException("Error: Response from State is null");
            }
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("State not found for workspace: {}", workspaceId);
            return null;
        }
    }

    public List<TagAttributes> getTags(String apiToken, String apiUrl, String workspaceId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl)
                .pathSegment("workspaces")
                .pathSegment(workspaceId)
                .pathSegment("relationships")
                .pathSegment("tags");

        String url = builder.toUriString();
        TagResponse response = makeRequest(apiToken, url, TagResponse.class);
        if (response != null) {
            return response.getData().stream()
                    .map(TagData::getAttributes)
                    .toList();
        } else {
            return Collections.emptyList();
        }
    }

    public Resource downloadState(String apiToken, String stateUrl) {
        return makeRequest(apiToken, stateUrl, Resource.class);
    }

    public String importWorkspace(String apiToken, String apiUrl, WorkspaceImportRequest workspaceImportRequest) {

        String result = "";

        // Create the workspace
        log.info("Importing Workspace: {}", workspaceImportRequest.getName());
        Workspace workspace = createWorkspaceFromRequest(workspaceImportRequest);
        try {
            workspace = workspaceRepository.save(workspace);
            log.info("Workspace created: {}", workspace.getId());
            result = "<li>Workspace created successfully.</li>";
        } catch (Exception e) {
            log.error(e.getMessage());
            result = "<li>There was an error creating the workspace:" + e.getMessage() + "</li>";
            return result;
        }

        // Import variables
        try {
            List<VariableAttributes> variablesImporter = getVariables(
                    apiToken,
                    apiUrl,
                    workspaceImportRequest.getOrganization(),
                    workspaceImportRequest.getId());

            importVariables(variablesImporter, workspace);
            log.info("Variables imported: {}", variablesImporter.size());
            if (variablesImporter.size() > 0)
                result += "<li>Variables imported successfully.</li>";
            else
                result += "<li>No variables to import.</li>";
        } catch (Exception e) {
            log.error(e.getMessage());
            result += "<li>There was an error importing the variables:" + e.getMessage() + "</li>";
        }

        // Import tags
        try {
            List<TagAttributes> tags = getTags(apiToken, apiUrl, workspaceImportRequest.getId());
            importTags(tags, workspace);
            log.info("Tags imported: {}", tags.size());
            if (tags.size() > 0)
                result += "<li>Tags imported successfully.</li>";
            else
                result += "<li>No tags to import.</li>";
        } catch (Exception e) {
            log.error(e.getMessage());
            result += "<li>There was an error importing the tags:" + e.getMessage() + "</li>";
        }

        // Import state
        StateVersion.Attributes lastState = getCurrentState(
                apiToken,
                apiUrl,
                workspaceImportRequest.getId());

        if (lastState == null) {
            result += "<li>No state to import.</li>";
            return result;
        }

        String stateDownloadUrl = lastState.getHostedStateDownloadUrl();
        String stateDownloadJsonUrl = lastState.getHostedJsonStateDownloadUrl();
        log.info("State download URL: {}", stateDownloadUrl);
        log.info("State download JSON URL: {}", stateDownloadJsonUrl);

        History history = new History();
        history.setWorkspace(workspace);
        history.setSerial(1);
        history.setMd5("0");
        history.setLineage("0");
        history.setOutput("");
        historyRepository.save(history);

        history.setOutput(String
                .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                        hostname,
                        workspace.getOrganization().getId().toString(),
                        workspace.getId().toString(),
                        history.getId().toString()));

        try {
            history = historyRepository.save(history);
            log.info("History created: {}", history.getId());
        } catch (Exception e) {
            log.error(e.getMessage());
            result += "<li>There was an error importing the state:" + e.getMessage() + "</li>";
        }

        // Download state
        try {
            Resource state = downloadState(apiToken, stateDownloadUrl);
            String terraformState = "";
            terraformState = readResourceToString(state);
            log.info("State downloaded: {}", terraformState.length());
            storageTypeService.uploadState(workspace.getOrganization().getId().toString(),
                    workspace.getId().toString(), terraformState, history.getId().toString());
            result += "<li>State imported successfully.</li>";

        } catch (IOException e) {
            log.error(e.getMessage());
            result += "<li>There was an error importing the state:" + e.getMessage() + "</li>";
            return result;
        }

        // Download JSON state
        try {
            Resource stateJson = downloadState(apiToken, stateDownloadJsonUrl);
            String terraformStateJson = "";
            terraformStateJson = readResourceToString(stateJson);
            log.info("State JSON downloaded: {}", terraformStateJson.length());
            storageTypeService.uploadTerraformStateJson(workspace.getOrganization().getId().toString(),
                    workspace.getId().toString(), terraformStateJson, history.getId().toString());
        } catch (Exception e) {
            log.error(e.getMessage());
            result += "<li><b>Warning:</b> The JSON state file was not available. This means you can still execute plan, apply, and destroy operations, but you will not be able to view the JSON output in the Terrakube UI. <a href='https://developer.hashicorp.com/terraform/cloud-docs/api-docs/state-versions' >This feature is accessible for workspaces utilizing Terraform v1.3.0 or later.</a> Error:" + e.getMessage() + "</li>";
            return result;
        }

        return result;
    }

    private String readResourceToString(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private Workspace createWorkspaceFromRequest(WorkspaceImportRequest workspaceImportRequest) {
        Workspace workspace = new Workspace();
        workspace.setName(workspaceImportRequest.getName());
        workspace.setDescription(workspaceImportRequest.getDescription());
        workspace.setTerraformVersion(workspaceImportRequest.getTerraformVersion());
        workspace.setExecutionMode(workspaceImportRequest.getExecutionMode().equals("local") ? "local" : "remote");

        // If the workspace has a VCS, set it
        if (workspaceImportRequest.getVcsId() != null && !workspaceImportRequest.getVcsId().isEmpty()) {
            UUID vcsId = UUID.fromString(workspaceImportRequest.getVcsId());
            vcsRepository.findById(vcsId).ifPresent(workspace::setVcs);
            // if branch is not set, set it to main
            workspace.setBranch(
                    workspaceImportRequest.getBranch() == null ? "main" : workspaceImportRequest.getBranch());
            workspace.setFolder(workspaceImportRequest.getFolder());
            workspace.setSource(workspaceImportRequest.getSource());
        } else {
            workspace.setBranch("remote-content");
            workspace.setSource("empty");
        }

        // Set the organization
        organizationRepository.findById(UUID.fromString(workspaceImportRequest.getOrganizationId()))
                .ifPresent(workspace::setOrganization);
        workspace.setIacType("terraform");

        return workspace;
    }

    private void importVariables(List<VariableAttributes> variablesImporter, Workspace workspace) {
        for (VariableAttributes variableAttribute : variablesImporter) {
            Variable variable = new Variable();
            variable.setKey(variableAttribute.getKey());
            variable.setValue(variableAttribute.getValue() != null ? variableAttribute.getValue() : "");
            variable.setDescription(variableAttribute.getDescription());
            variable.setSensitive(variableAttribute.isSensitive());
            variable.setCategory("env".equals(variableAttribute.getCategory()) ? Category.ENV : Category.TERRAFORM);
            variable.setHcl(variableAttribute.isHcl());
            variable.setWorkspace(workspace);
            variableRepository.save(variable);
        }
    }

    private void importTags(List<TagAttributes> tags, Workspace workspace) {
        for (TagAttributes tagAttribute : tags) {

            // check if tag exists if not create it
            Tag tag = tagRepository.getByOrganizationNameAndName(workspace.getOrganization().getName(),
                    tagAttribute.getName());
            if (tag == null) {
                tag = new Tag();
                tag.setName(tagAttribute.getName());
                tag.setOrganization(workspace.getOrganization());
                tag = tagRepository.save(tag);
            }
            WorkspaceTag workspaceTag = new WorkspaceTag();
            workspaceTag.setWorkspace(workspace);
            workspaceTag.setTagId(tag.getId().toString());
            workspaceTagRepository.save(workspaceTag);
        }
    }
}
