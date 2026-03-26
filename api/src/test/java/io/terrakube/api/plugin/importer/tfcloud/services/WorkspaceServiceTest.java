package io.terrakube.api.plugin.importer.tfcloud.services;

import io.terrakube.api.plugin.importer.tfcloud.*;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    private WorkspaceService workspaceService;

    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private HistoryRepository historyRepository;
    @Mock
    private VcsRepository vcsRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private VariableRepository variableRepository;
    @Mock
    private WorkspaceTagRepository workspaceTagRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private StorageTypeService storageTypeService;
    @Mock
    private RestTemplate restTemplate;

    private final String hostname = "terrakube.local";
    private final String apiToken = "test-token";
    private final String apiUrl = "https://app.terraform.io/api/v2";
    private final String organization = "test-org";

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                hostname,
                workspaceRepository,
                historyRepository,
                storageTypeService,
                vcsRepository,
                organizationRepository,
                variableRepository,
                workspaceTagRepository,
                tagRepository
        );
        ReflectionTestUtils.setField(workspaceService, "restTemplate", restTemplate);
    }

    @Test
    void testGetWorkspaces() {
        WorkspaceListResponse response = new WorkspaceListResponse();
        WorkspaceImport.WorkspaceData data = new WorkspaceImport.WorkspaceData();
        data.setId("ws-1");
        response.setData(Collections.singletonList(data));

        WorkspaceListResponse.WorkspaceMeta meta = new WorkspaceListResponse.WorkspaceMeta();
        WorkspaceListResponse.WorkspaceMeta.Pagination pagination = new WorkspaceListResponse.WorkspaceMeta.Pagination();
        pagination.setNextPage(null);
        meta.setPagination(pagination);
        response.setMeta(meta);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(WorkspaceListResponse.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        List<WorkspaceImport.WorkspaceData> result = workspaceService.getWorkspaces(apiToken, apiUrl, organization);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("ws-1", result.get(0).getId());
    }

    @Test
    void testGetVariables() {
        VariableResponse response = new VariableResponse();
        VariableResponse.VariableData data = new VariableResponse.VariableData();
        VariableResponse.VariableData.VariableAttributes attributes = new VariableResponse.VariableData.VariableAttributes();
        attributes.setKey("test-key");
        data.setAttributes(attributes);
        response.setData(Collections.singletonList(data));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(VariableResponse.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        List<VariableResponse.VariableData.VariableAttributes> result = workspaceService.getVariables(apiToken, apiUrl, organization, "test-ws");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("test-key", result.get(0).getKey());
    }

    @Test
    void testGetCurrentState() {
        StateVersion stateVersion = new StateVersion();
        StateVersion.Data data = new StateVersion.Data();
        StateVersion.Attributes attributes = new StateVersion.Attributes();
        attributes.setHostedStateDownloadUrl("https://download.url");
        data.setAttributes(attributes);
        stateVersion.setData(data);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(StateVersion.class)))
                .thenReturn(new ResponseEntity<>(stateVersion, HttpStatus.OK));

        StateVersion.Attributes result = workspaceService.getCurrentState(apiToken, apiUrl, "ws-1");

        assertNotNull(result);
        assertEquals("https://download.url", result.getHostedStateDownloadUrl());
    }

    @Test
    void testGetCurrentStateNotFound() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(StateVersion.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        StateVersion.Attributes result = workspaceService.getCurrentState(apiToken, apiUrl, "ws-1");

        assertNull(result);
    }

    @Test
    void testGetCurrentStateNullResponse() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(StateVersion.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThrows(WorkspaceService.NullResponseException.class, () -> {
            workspaceService.getCurrentState(apiToken, apiUrl, "ws-1");
        });
    }

    @Test
    void testGetTags() {
        TagResponse response = new TagResponse();
        TagResponse.TagData data = new TagResponse.TagData();
        TagResponse.TagData.TagAttributes attributes = new TagResponse.TagData.TagAttributes();
        attributes.setName("test-tag");
        data.setAttributes(attributes);
        response.setData(Collections.singletonList(data));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(TagResponse.class)))
                .thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        List<TagResponse.TagData.TagAttributes> result = workspaceService.getTags(apiToken, apiUrl, "ws-1");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("test-tag", result.get(0).getName());
    }

    @Test
    void testDownloadState() {
        Resource resource = new ByteArrayResource("test-state".getBytes());
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Resource.class)))
                .thenReturn(new ResponseEntity<>(resource, HttpStatus.OK));

        Resource result = workspaceService.downloadState(apiToken, "https://download.url");

        assertNotNull(result);
        verify(restTemplate).exchange(eq("https://download.url"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Resource.class));
    }

    @Test
    void testImportWorkspace() throws IOException {
        WorkspaceImportRequest request = new WorkspaceImportRequest();
        request.setName("test-ws");
        request.setOrganization("test-org");
        request.setOrganizationId(UUID.randomUUID().toString());
        request.setVcsId(UUID.randomUUID().toString());
        request.setId("ws-tfc-1");
        request.setExecutionMode("remote");

        Organization org = new Organization();
        org.setId(UUID.fromString(request.getOrganizationId()));
        when(organizationRepository.findById(any())).thenReturn(Optional.of(org));

        Vcs vcs = new Vcs();
        vcs.setId(UUID.fromString(request.getVcsId()));
        when(vcsRepository.findById(any())).thenReturn(Optional.of(vcs));

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(org);
        when(workspaceRepository.save(any())).thenReturn(workspace);

        // Variables mock
        VariableResponse varResponse = new VariableResponse();
        varResponse.setData(Collections.emptyList());
        when(restTemplate.exchange(contains("/vars"), eq(HttpMethod.GET), any(HttpEntity.class), eq(VariableResponse.class)))
                .thenReturn(new ResponseEntity<>(varResponse, HttpStatus.OK));

        // Tags mock
        TagResponse tagResponse = new TagResponse();
        tagResponse.setData(Collections.emptyList());
        when(restTemplate.exchange(contains("/tags"), eq(HttpMethod.GET), any(HttpEntity.class), eq(TagResponse.class)))
                .thenReturn(new ResponseEntity<>(tagResponse, HttpStatus.OK));

        // State mock
        StateVersion stateVersion = new StateVersion();
        StateVersion.Data stateData = new StateVersion.Data();
        StateVersion.Attributes stateAttributes = new StateVersion.Attributes();
        stateAttributes.setHostedStateDownloadUrl("https://state.url");
        stateAttributes.setHostedJsonStateDownloadUrl("https://state.json.url");
        stateData.setAttributes(stateAttributes);
        stateVersion.setData(stateData);
        when(restTemplate.exchange(contains("/current-state-version"), eq(HttpMethod.GET), any(HttpEntity.class), eq(StateVersion.class)))
                .thenReturn(new ResponseEntity<>(stateVersion, HttpStatus.OK));

        Resource stateResource = new ByteArrayResource("{}".getBytes());
        when(restTemplate.exchange(eq("https://state.url"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Resource.class)))
                .thenReturn(new ResponseEntity<>(stateResource, HttpStatus.OK));
        when(restTemplate.exchange(eq("https://state.json.url"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Resource.class)))
                .thenReturn(new ResponseEntity<>(stateResource, HttpStatus.OK));

        when(historyRepository.save(any())).thenAnswer(invocation -> {
            History h = invocation.getArgument(0);
            if (h.getId() == null) {
                h.setId(UUID.randomUUID());
            }
            return h;
        });

        String result = workspaceService.importWorkspace(apiToken, apiUrl, request);

        assertNotNull(result);
        assertTrue(result.contains("Workspace created successfully"));
        assertTrue(result.contains("State imported successfully"));
        
        verify(workspaceRepository).save(any());
        verify(historyRepository, atLeastOnce()).save(any());
        verify(storageTypeService).uploadState(anyString(), anyString(), anyString(), anyString());
        verify(storageTypeService).uploadTerraformStateJson(anyString(), anyString(), anyString(), anyString());
    }
}
