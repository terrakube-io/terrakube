package io.terrakube.api.plugin.importer.tfcloud.services;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import io.terrakube.api.plugin.importer.tfcloud.StateVersion;
import io.terrakube.api.plugin.importer.tfcloud.TagResponse;
import io.terrakube.api.plugin.importer.tfcloud.TagResponse.TagData;
import io.terrakube.api.plugin.importer.tfcloud.TagResponse.TagData.TagAttributes;
import io.terrakube.api.plugin.importer.tfcloud.ImportedSensitiveVariable;
import io.terrakube.api.plugin.importer.tfcloud.SensitiveVariableImportPreview;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse.VariableData;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse.VariableData.VariableAttributes;
import io.terrakube.api.plugin.importer.tfcloud.VarsetListResponse;
import io.terrakube.api.plugin.importer.tfcloud.VarsetSummary;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImport;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImportRequest;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceListResponse;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.CollectionRepository;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import io.terrakube.api.rs.ExecutionMode;
import io.terrakube.api.rs.tag.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class WorkspaceService {

    private static final int MAX_RATE_LIMIT_ATTEMPTS = 3;
    private static final long INITIAL_RATE_LIMIT_BACKOFF_MILLIS = 1000L;
    private static final long MAX_RATE_LIMIT_BACKOFF_MILLIS = 5000L;

    private record VariableImportSummary(int importedCount, int incompleteSensitiveCount, int discardedSensitiveCount) {
    }

    private final RestTemplate restTemplate;
    WorkspaceRepository workspaceRepository;
    HistoryRepository historyRepository;
    VcsRepository vcsRepository;
    OrganizationRepository organizationRepository;
    VariableRepository variableRepository;
    WorkspaceTagRepository workspaceTagRepository;
    TagRepository tagRepository;
    CollectionRepository collectionRepository;
    ReferenceRepository referenceRepository;

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
            CollectionRepository collectionRepository,
            ReferenceRepository referenceRepository) {
        this.restTemplate = new RestTemplate();
        this.workspaceRepository = workspaceRepository;
        this.historyRepository = historyRepository;
        this.storageTypeService = storageTypeService;
        this.vcsRepository = vcsRepository;
        this.organizationRepository = organizationRepository;
        this.variableRepository = variableRepositor;
        this.hostname = hostname;
        this.workspaceTagRepository = workspaceTagRepository;
        this.tagRepository = tagRepository;
        this.collectionRepository = collectionRepository;
        this.referenceRepository = referenceRepository;
    }

    public class NullResponseException extends RuntimeException {
        public NullResponseException(String message) {
            super(message);
        }
    }

    private <T> T makeRequest(String apiToken, String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiToken);
        headers.setContentType(MediaType.valueOf("application/vnd.api+json"));
        HttpEntity<String> entity = new HttpEntity<>(headers);

        for (int attempt = 1; attempt <= MAX_RATE_LIMIT_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
                return response.getBody();
            } catch (HttpClientErrorException.TooManyRequests exception) {
                if (attempt == MAX_RATE_LIMIT_ATTEMPTS) {
                    throw exception;
                }

                long delayMillis = getRateLimitRetryDelayMillis(exception, attempt);
                log.warn(
                        "Terraform Cloud rate limit reached for {}. Retrying in {} ms (attempt {} of {}).",
                        url,
                        delayMillis,
                        attempt + 1,
                        MAX_RATE_LIMIT_ATTEMPTS);
                sleepBeforeRetry(delayMillis);
            }
        }

        throw new IllegalStateException("Terraform Cloud request retry loop completed without a response.");
    }

    private long getRateLimitRetryDelayMillis(HttpClientErrorException.TooManyRequests exception, int attempt) {
        HttpHeaders responseHeaders = exception.getResponseHeaders();
        if (responseHeaders != null) {
            String retryAfter = responseHeaders.getFirst(HttpHeaders.RETRY_AFTER);
            if (StringUtils.hasText(retryAfter)) {
                try {
                    return Math.max(Long.parseLong(retryAfter.trim()), 0L) * 1000L;
                } catch (NumberFormatException parseException) {
                    log.debug("Unable to parse Retry-After header value: {}", retryAfter);
                }
            }
        }

        long calculatedDelay = INITIAL_RATE_LIMIT_BACKOFF_MILLIS * (1L << (attempt - 1));
        return Math.min(calculatedDelay, MAX_RATE_LIMIT_BACKOFF_MILLIS);
    }

    private void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a Terraform Cloud request.", interruptedException);
        }
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

            if (response.getMeta() == null
                    || response.getMeta().getPagination() == null
                    || response.getMeta().getPagination().getNextPage() == null) {
                break;
            }

            currentPage = response.getMeta().getPagination().getNextPage();
        }

        return allData;
    }

    public List<VarsetSummary> getWorkspaceVarsets(String apiToken, String apiUrl, String workspaceId) {
        List<VarsetSummary> allData = new ArrayList<>();
        int currentPage = 1;

        while (true) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl)
                    .pathSegment("workspaces")
                    .pathSegment(workspaceId)
                    .pathSegment("varsets");

            String url = builder.toUriString() + "?page[size]=50&page[number]=" + currentPage;
            VarsetListResponse response = makeRequest(apiToken, url, VarsetListResponse.class);

            if (response == null || response.getData() == null) {
                break;
            }

            allData.addAll(response.getData().stream()
                    .map(this::toVarsetSummary)
                    .filter(Objects::nonNull)
                    .toList());

            if (response.getMeta() == null
                    || response.getMeta().getPagination() == null
                    || response.getMeta().getPagination().getNextPage() == null) {
                break;
            }

            currentPage = response.getMeta().getPagination().getNextPage();
        }

        return allData;
    }

    private VarsetSummary toVarsetSummary(VarsetListResponse.VarsetData varset) {
        if (varset == null) {
            log.warn("Skipping null Terraform Cloud variable set entry");
            return null;
        }

        if (varset.getAttributes() == null) {
            log.warn("Skipping Terraform Cloud variable set {} because attributes are missing", varset.getId());
            return null;
        }

        String varsetName = varset.getAttributes().getName();
        if (!StringUtils.hasText(varsetName)) {
            if (StringUtils.hasText(varset.getId())) {
                varsetName = "Unnamed variable collection (" + varset.getId() + ")";
            } else {
                varsetName = "Unnamed variable collection";
            }
        }

        return new VarsetSummary(varset.getId(), varsetName);
    }

    public List<VariableData> getVariables(String apiToken, String apiUrl, String workspaceName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl)
                .pathSegment("workspaces")
                .pathSegment(workspaceName)
                .pathSegment("vars");

        String url = builder.toUriString();
        VariableResponse response = makeRequest(apiToken, url, VariableResponse.class);
        if (response != null) {
            return response.getData();
        } else {
            return Collections.emptyList();
        }
    }

    public List<SensitiveVariableImportPreview> getSensitiveVariables(String apiToken, String apiUrl, String workspaceName) {
        return getVariables(apiToken, apiUrl, workspaceName).stream()
                .filter(Objects::nonNull)
                .filter(variableData -> variableData.getAttributes() != null)
                .filter(variableData -> variableData.getAttributes().isSensitive())
                .map(variableData -> {
                    VariableAttributes attributes = variableData.getAttributes();
                    return new SensitiveVariableImportPreview(
                            variableData.getId(),
                            attributes.getKey(),
                            attributes.getDescription(),
                            attributes.getCategory(),
                            attributes.isHcl());
                })
                .toList();
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
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiToken);

        ResponseEntity<Resource> response = restTemplate.exchange(
                stateUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Resource.class);

        if (response != null && response.getBody() != null) {
            return response.getBody();
        } else {
            throw new NullResponseException("Error: Response from State is null");
        }
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
            result = "<li>There was an error creating the workspace:" + escapeHtml(e.getMessage()) + "</li>";
            return result;
        }

        // Attach variable collections
        try {
            result += importVariableCollections(workspaceImportRequest, workspace);
        } catch (Exception e) {
            log.error("Error linking variable collections for workspace {}", workspaceImportRequest.getName(), e);
            result += "<li><b>Warning:</b> There was an error linking variable collections:" + escapeHtml(e.getMessage()) + "</li>";
        }

        // Import variables
        try {
            List<VariableData> variablesImporter = getVariables(
                    apiToken,
                    apiUrl,
                    workspaceImportRequest.getId());

            VariableImportSummary importSummary = importVariables(
                    variablesImporter,
                    workspaceImportRequest.getSensitiveVariables(),
                    workspace);
            log.info("Variables imported: {}", importSummary.importedCount());
            if (importSummary.importedCount() > 0) {
                result += "<li>Variables imported successfully.</li>";
            } else {
                result += "<li>No variables to import.</li>";
            }
            if (importSummary.incompleteSensitiveCount() > 0) {
                result += "<li><b>Warning:</b> Imported "
                        + importSummary.incompleteSensitiveCount()
                        + " sensitive variable(s) without a value. They were marked incomplete and must be completed before a run can start.</li>";
            }
            if (importSummary.discardedSensitiveCount() > 0) {
                result += "<li>Discarded "
                        + importSummary.discardedSensitiveCount()
                        + " sensitive variable(s) during import.</li>";
            }
        } catch (Exception e) {
            log.error(e.getMessage());
            result += "<li>There was an error importing the variables:" + escapeHtml(e.getMessage()) + "</li>";
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
            result += "<li>There was an error importing the tags:" + escapeHtml(e.getMessage()) + "</li>";
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
            result += "<li>There was an error importing the state:" + escapeHtml(e.getMessage()) + "</li>";
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
            result += "<li>There was an error importing the state:" + escapeHtml(e.getMessage()) + "</li>";
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
            result += "<li><b>Warning:</b> The JSON state file was not available. This means you can still execute plan, apply, and destroy operations, but you will not be able to view the JSON output in the Terrakube UI. <a href='https://developer.hashicorp.com/terraform/cloud-docs/api-docs/state-versions' >This feature is accessible for workspaces utilizing Terraform v1.3.0 or later.</a> Error:" + escapeHtml(e.getMessage()) + "</li>";
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
        String executionMode = workspaceImportRequest.getExecutionMode();
        workspace.setExecutionMode("local".equals(executionMode) ? ExecutionMode.local : ExecutionMode.remote);

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

    private String importVariableCollections(WorkspaceImportRequest workspaceImportRequest, Workspace workspace) {
        List<String> variableCollectionIds = workspaceImportRequest.getVariableCollectionIds();
        if (variableCollectionIds == null || variableCollectionIds.isEmpty()) {
            return "<li>No variable collections selected.</li>";
        }

        UUID organizationId = UUID.fromString(workspaceImportRequest.getOrganizationId());
        LinkedHashSet<String> uniqueCollectionIds = variableCollectionIds.stream()
                .filter(collectionId -> collectionId != null && !collectionId.trim().isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (uniqueCollectionIds.isEmpty()) {
            return "<li>No variable collections selected.</li>";
        }

        int linkedCollections = 0;
        StringBuilder result = new StringBuilder();

        for (String collectionIdValue : uniqueCollectionIds) {
            UUID collectionId;
            try {
                collectionId = UUID.fromString(collectionIdValue);
            } catch (IllegalArgumentException exception) {
                result.append("<li><b>Warning:</b> Skipped invalid variable collection ID: ")
                        .append(escapeHtml(collectionIdValue))
                        .append("</li>");
                continue;
            }

            Collection collection = collectionRepository.findById(collectionId).orElse(null);
            if (collection == null) {
                result.append("<li><b>Warning:</b> Variable collection was not found: ")
                        .append(collectionId)
                        .append("</li>");
                continue;
            }

            if (collection.getOrganization() == null
                    || collection.getOrganization().getId() == null
                    || !organizationId.equals(collection.getOrganization().getId())) {
                result.append("<li><b>Warning:</b> Skipped variable collection outside the destination organization: ")
                        .append(collectionId)
                        .append("</li>");
                continue;
            }

            if (referenceRepository.existsByWorkspaceAndCollection(workspace, collection)) {
                continue;
            }

            try {
                Reference reference = new Reference();
                reference.setCollection(collection);
                reference.setWorkspace(workspace);
                reference.setDescription(
                        "Reference created during Terraform Cloud import for collection " + collection.getName());
                referenceRepository.save(reference);
                linkedCollections++;
            } catch (Exception exception) {
                log.error("Error linking variable collection {}", collectionId, exception);
                result.append("<li><b>Warning:</b> Failed to link variable collection: ")
                        .append(collectionId)
                        .append("</li>");
            }
        }

        if (linkedCollections > 0) {
            result.insert(0, "<li>Variable collections linked successfully: " + linkedCollections + "</li>");
        } else if (result.length() == 0) {
            result.append("<li>No new variable collections to link.</li>");
        }

        return result.toString();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return HtmlUtils.htmlEscape(value);
    }

    private VariableImportSummary importVariables(List<VariableData> variablesImporter,
            List<ImportedSensitiveVariable> selectedSensitiveVariables, Workspace workspace) {
        int importedCount = 0;
        int incompleteSensitiveCount = 0;
        int discardedSensitiveCount = 0;

        var sensitiveVariableSelections = (selectedSensitiveVariables == null ? List.<ImportedSensitiveVariable>of()
                : selectedSensitiveVariables)
                .stream()
                .filter(Objects::nonNull)
                .filter(variable -> StringUtils.hasText(variable.getSourceVariableId()))
                .collect(Collectors.toMap(
                        ImportedSensitiveVariable::getSourceVariableId,
                        variable -> variable,
                        (left, right) -> left));

        for (VariableData variableData : variablesImporter) {
            if (variableData == null || variableData.getAttributes() == null) {
                continue;
            }

            VariableAttributes variableAttribute = variableData.getAttributes();
            String value = variableAttribute.getValue() != null ? variableAttribute.getValue() : "";

            if (variableAttribute.isSensitive()) {
                ImportedSensitiveVariable selectedSensitiveVariable = sensitiveVariableSelections.get(variableData.getId());
                if (selectedSensitiveVariable == null) {
                    discardedSensitiveCount++;
                    continue;
                }

                value = selectedSensitiveVariable.getValue() != null ? selectedSensitiveVariable.getValue() : "";
            }

            Variable variable = new Variable();
            variable.setKey(variableAttribute.getKey());
            variable.setValue(value);
            variable.setDescription(variableAttribute.getDescription());
            variable.setSensitive(variableAttribute.isSensitive());
            variable.setCategory("env".equals(variableAttribute.getCategory()) ? Category.ENV : Category.TERRAFORM);
            variable.setHcl(variableAttribute.isHcl());
            variable.setIncomplete(variableAttribute.isSensitive() && !StringUtils.hasText(value));
            variable.setWorkspace(workspace);
            variableRepository.save(variable);
            importedCount++;

            if (variable.isIncomplete()) {
                incompleteSensitiveCount++;
            }
        }

        return new VariableImportSummary(importedCount, incompleteSensitiveCount, discardedSensitiveCount);
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
