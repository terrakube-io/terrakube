package io.terrakube.api.plugin.importer.tfcloud.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import io.terrakube.api.plugin.importer.tfcloud.ImportedSensitiveVariable;
import io.terrakube.api.plugin.importer.tfcloud.VarsetSummary;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse.VariableData;
import io.terrakube.api.plugin.importer.tfcloud.VariableResponse.VariableData.VariableAttributes;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImportRequest;
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
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Variable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private HistoryRepository historyRepository;

    @Mock
    private StorageTypeService storageTypeService;

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
    private CollectionRepository collectionRepository;

    @Mock
    private ReferenceRepository referenceRepository;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                "localhost:8080",
                workspaceRepository,
                historyRepository,
                storageTypeService,
                vcsRepository,
                organizationRepository,
                variableRepository,
                workspaceTagRepository,
                tagRepository,
                collectionRepository,
                referenceRepository);
    }

    @Test
    void shouldFetchAllWorkspaceVarsetsAcrossPages() {
        RestTemplate restTemplate = new RestTemplate();
        ReflectionTestUtils.setField(workspaceService, "restTemplate", restTemplate);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://app.terraform.io/api/v2/workspaces/ws-123/varsets?page%5Bsize%5D=50&page%5Bnumber%5D=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {
                              "id": "varset-1",
                              "type": "varsets",
                              "attributes": {
                                "name": "shared-prod"
                              }
                            }
                          ],
                          "meta": {
                            "pagination": {
                              "current-page": 1,
                              "next-page": 2,
                              "prev-page": null,
                              "total-pages": 2,
                              "total-count": 2
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://app.terraform.io/api/v2/workspaces/ws-123/varsets?page%5Bsize%5D=50&page%5Bnumber%5D=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {
                              "id": "varset-2",
                              "type": "varsets",
                              "attributes": {
                                "name": "shared-common"
                              }
                            }
                          ],
                          "meta": {
                            "pagination": {
                              "current-page": 2,
                              "next-page": null,
                              "prev-page": 1,
                              "total-pages": 2,
                              "total-count": 2
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        List<VarsetSummary> varsets = workspaceService.getWorkspaceVarsets(
                "token",
                "https://app.terraform.io/api/v2",
                "ws-123");

        assertThat(varsets)
                .extracting(VarsetSummary::getName)
                .containsExactly("shared-prod", "shared-common");

        server.verify();
    }

    @Test
    void shouldSkipWorkspaceVarsetsWithNullAttributes() {
        RestTemplate restTemplate = new RestTemplate();
        ReflectionTestUtils.setField(workspaceService, "restTemplate", restTemplate);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://app.terraform.io/api/v2/workspaces/ws-123/varsets?page%5Bsize%5D=50&page%5Bnumber%5D=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {
                              "id": "varset-missing-attributes",
                              "type": "varsets",
                              "attributes": null
                            },
                            {
                              "id": "varset-valid",
                              "type": "varsets",
                              "attributes": {
                                "name": "shared-common"
                              }
                            }
                          ],
                          "meta": {
                            "pagination": {
                              "current-page": 1,
                              "next-page": null,
                              "prev-page": null,
                              "total-pages": 1,
                              "total-count": 2
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        List<VarsetSummary> varsets = workspaceService.getWorkspaceVarsets(
                "token",
                "https://app.terraform.io/api/v2",
                "ws-123");

        assertThat(varsets)
                .extracting(VarsetSummary::getName)
                .containsExactly("shared-common");

        server.verify();
    }

    @Test
    void shouldAttachDedupedCollectionsAndWarnForInvalidOnesDuringImport() {
        WorkspaceService serviceSpy = spy(workspaceService);

        UUID organizationId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID validCollectionId = UUID.randomUUID();
        UUID missingCollectionId = UUID.randomUUID();
        UUID crossOrgCollectionId = UUID.randomUUID();

        Organization destinationOrganization = new Organization();
        destinationOrganization.setId(organizationId);
        destinationOrganization.setName("destination-org");

        Organization otherOrganization = new Organization();
        otherOrganization.setId(UUID.randomUUID());
        otherOrganization.setName("other-org");

        Collection validCollection = new Collection();
        validCollection.setId(validCollectionId);
        validCollection.setName("valid-collection");
        validCollection.setOrganization(destinationOrganization);

        Collection crossOrgCollection = new Collection();
        crossOrgCollection.setId(crossOrgCollectionId);
        crossOrgCollection.setName("foreign-collection");
        crossOrgCollection.setOrganization(otherOrganization);

        WorkspaceImportRequest request = new WorkspaceImportRequest();
        request.setId("ws-123");
        request.setOrganizationId(organizationId.toString());
        request.setOrganization("source-org");
        request.setName("imported-workspace");
        request.setTerraformVersion("1.8.5");
        request.setExecutionMode("remote");
        request.setDescription("Imported from Terraform Cloud");
        request.setVariableCollectionIds(List.of(
                validCollectionId.toString(),
                validCollectionId.toString(),
                "not-a-uuid",
                missingCollectionId.toString(),
                crossOrgCollectionId.toString()));

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(destinationOrganization));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace workspace = invocation.getArgument(0);
            workspace.setId(workspaceId);
            return workspace;
        });
        when(collectionRepository.findById(validCollectionId)).thenReturn(Optional.of(validCollection));
        when(collectionRepository.findById(missingCollectionId)).thenReturn(Optional.empty());
        when(collectionRepository.findById(crossOrgCollectionId)).thenReturn(Optional.of(crossOrgCollection));

        doReturn(Collections.emptyList()).when(serviceSpy).getVariables(anyString(), anyString(), anyString());
        doReturn(Collections.emptyList()).when(serviceSpy).getTags(anyString(), anyString(), anyString());
        doReturn(null).when(serviceSpy).getCurrentState(anyString(), anyString(), anyString());

        String result = serviceSpy.importWorkspace("token", "https://app.terraform.io/api/v2", request);

        assertThat(result).contains("Workspace created successfully.");
        assertThat(result).contains("Variable collections linked successfully: 1");
        assertThat(result).contains("Skipped invalid variable collection ID: not-a-uuid");
        assertThat(result).contains("Variable collection was not found: " + missingCollectionId);
        assertThat(result).contains("Skipped variable collection outside the destination organization: " + crossOrgCollectionId);
        assertThat(result).contains("No variables to import.");
        assertThat(result).contains("No tags to import.");
        assertThat(result).contains("No state to import.");

        verify(collectionRepository, times(1)).findById(validCollectionId);
        verify(referenceRepository, times(1)).save(any(Reference.class));

        ArgumentCaptor<Reference> referenceCaptor = ArgumentCaptor.forClass(Reference.class);
        verify(referenceRepository).save(referenceCaptor.capture());
        assertThat(referenceCaptor.getValue().getCollection()).isSameAs(validCollection);
        assertThat(referenceCaptor.getValue().getWorkspace().getId()).isEqualTo(workspaceId);
    }

    @Test
    void shouldDefaultExecutionModeToRemoteWhenMissing() {
        WorkspaceService serviceSpy = spy(workspaceService);

        UUID organizationId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        Organization destinationOrganization = new Organization();
        destinationOrganization.setId(organizationId);
        destinationOrganization.setName("destination-org");

        WorkspaceImportRequest request = new WorkspaceImportRequest();
        request.setId("ws-123");
        request.setOrganizationId(organizationId.toString());
        request.setOrganization("source-org");
        request.setName("imported-workspace");
        request.setTerraformVersion("1.8.5");
        request.setExecutionMode(null);
        request.setDescription("Imported from Terraform Cloud");

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(destinationOrganization));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace workspace = invocation.getArgument(0);
            workspace.setId(workspaceId);
            return workspace;
        });

        doReturn(Collections.emptyList()).when(serviceSpy).getVariables(anyString(), anyString(), anyString());
        doReturn(Collections.emptyList()).when(serviceSpy).getTags(anyString(), anyString(), anyString());
        doReturn(null).when(serviceSpy).getCurrentState(anyString(), anyString(), anyString());

        String result = serviceSpy.importWorkspace("token", "https://app.terraform.io/api/v2", request);

        assertThat(result).contains("Workspace created successfully.");

        ArgumentCaptor<Workspace> workspaceCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(workspaceCaptor.capture());
        assertThat(workspaceCaptor.getValue().getExecutionMode()).isEqualTo(io.terrakube.api.rs.ExecutionMode.remote);
    }

    @Test
    void shouldSkipCreatingReferenceWhenCollectionAlreadyLinked() {
        WorkspaceService serviceSpy = spy(workspaceService);

        UUID organizationId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID validCollectionId = UUID.randomUUID();

        Organization destinationOrganization = new Organization();
        destinationOrganization.setId(organizationId);
        destinationOrganization.setName("destination-org");

        Collection validCollection = new Collection();
        validCollection.setId(validCollectionId);
        validCollection.setName("valid-collection");
        validCollection.setOrganization(destinationOrganization);

        WorkspaceImportRequest request = new WorkspaceImportRequest();
        request.setId("ws-123");
        request.setOrganizationId(organizationId.toString());
        request.setOrganization("source-org");
        request.setName("imported-workspace");
        request.setTerraformVersion("1.8.5");
        request.setExecutionMode("remote");
        request.setDescription("Imported from Terraform Cloud");
        request.setVariableCollectionIds(List.of(validCollectionId.toString()));

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(destinationOrganization));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace workspace = invocation.getArgument(0);
            workspace.setId(workspaceId);
            return workspace;
        });
        when(collectionRepository.findById(validCollectionId)).thenReturn(Optional.of(validCollection));
        when(referenceRepository.existsByWorkspaceAndCollection(any(Workspace.class), any(Collection.class))).thenReturn(true);

        doReturn(Collections.emptyList()).when(serviceSpy).getVariables(anyString(), anyString(), anyString());
        doReturn(Collections.emptyList()).when(serviceSpy).getTags(anyString(), anyString(), anyString());
        doReturn(null).when(serviceSpy).getCurrentState(anyString(), anyString(), anyString());

        String result = serviceSpy.importWorkspace("token", "https://app.terraform.io/api/v2", request);

        assertThat(result).contains("No new variable collections to link.");
        verify(referenceRepository, never()).save(any(Reference.class));
    }

    @Test
    void shouldReturnSensitiveVariablePreviewsOnly() {
        RestTemplate restTemplate = new RestTemplate();
        ReflectionTestUtils.setField(workspaceService, "restTemplate", restTemplate);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://app.terraform.io/api/v2/workspaces/ws-123/vars"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {
                              "id": "var-1",
                              "type": "vars",
                              "attributes": {
                                "key": "regular_value",
                                "value": "hello",
                                "description": "plain text",
                                "sensitive": false,
                                "category": "terraform",
                                "hcl": false
                              }
                            },
                            {
                              "id": "var-2",
                              "type": "vars",
                              "attributes": {
                                "key": "sensitive_token",
                                "value": null,
                                "description": "masked",
                                "sensitive": true,
                                "category": "env",
                                "hcl": false
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        var previews = workspaceService.getSensitiveVariables(
                "token",
                "https://app.terraform.io/api/v2",
                "ws-123");

        assertThat(previews)
                .extracting("id", "key", "description", "category")
                .containsExactly(tuple("var-2", "sensitive_token", "masked", "env"));

        server.verify();
    }

    @Test
    void shouldRetryWorkspaceVariableRequestsWhenTerraformCloudRateLimits() {
        RestTemplate restTemplate = new RestTemplate();
        ReflectionTestUtils.setField(workspaceService, "restTemplate", restTemplate);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://app.terraform.io/api/v2/workspaces/ws-123/vars"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "errors": [
                                    {
                                      "status": "429",
                                      "title": "Too Many Requests"
                                    }
                                  ]
                                }
                                """));

        server.expect(requestTo("https://app.terraform.io/api/v2/workspaces/ws-123/vars"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {
                              "id": "var-2",
                              "type": "vars",
                              "attributes": {
                                "key": "sensitive_token",
                                "value": null,
                                "description": "masked",
                                "sensitive": true,
                                "category": "env",
                                "hcl": false
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        var variables = workspaceService.getVariables(
                "token",
                "https://app.terraform.io/api/v2",
                "ws-123");

        assertThat(variables)
                .extracting(VariableData::getId)
                .containsExactly("var-2");

        server.verify();
    }

    @Test
    void shouldImportSelectedSensitiveVariablesAndMarkEmptyValuesIncomplete() {
        WorkspaceService serviceSpy = spy(workspaceService);

        UUID organizationId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        Organization destinationOrganization = new Organization();
        destinationOrganization.setId(organizationId);
        destinationOrganization.setName("destination-org");

        WorkspaceImportRequest request = new WorkspaceImportRequest();
        request.setId("ws-123");
        request.setOrganizationId(organizationId.toString());
        request.setOrganization("source-org");
        request.setName("imported-workspace");
        request.setTerraformVersion("1.8.5");
        request.setExecutionMode("remote");
        request.setDescription("Imported from Terraform Cloud");

        ImportedSensitiveVariable selectedSensitiveVariable = new ImportedSensitiveVariable();
        selectedSensitiveVariable.setSourceVariableId("var-sensitive-keep");
        selectedSensitiveVariable.setValue("");
        request.setSensitiveVariables(List.of(selectedSensitiveVariable));

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(destinationOrganization));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace workspace = invocation.getArgument(0);
            workspace.setId(workspaceId);
            return workspace;
        });

        doReturn(List.of(
                buildVariableData("var-plain", "regular_value", "hello", false, "terraform", false),
                buildVariableData("var-sensitive-keep", "sensitive_token", null, true, "env", false),
                buildVariableData("var-sensitive-discard", "unused_secret", null, true, "terraform", true)))
                .when(serviceSpy)
                .getVariables(anyString(), anyString(), anyString());
        doReturn(Collections.emptyList()).when(serviceSpy).getTags(anyString(), anyString(), anyString());
        doReturn(null).when(serviceSpy).getCurrentState(anyString(), anyString(), anyString());

        String result = serviceSpy.importWorkspace("token", "https://app.terraform.io/api/v2", request);

        assertThat(result).contains("Variables imported successfully.");
        assertThat(result).contains("Imported 1 sensitive variable(s) without a value. They were marked incomplete");
        assertThat(result).contains("Discarded 1 sensitive variable(s) during import.");

        ArgumentCaptor<Variable> variableCaptor = ArgumentCaptor.forClass(Variable.class);
        verify(variableRepository, times(2)).save(variableCaptor.capture());

        List<Variable> savedVariables = variableCaptor.getAllValues();
        assertThat(savedVariables)
                .extracting(Variable::getKey, Variable::isSensitive, Variable::isIncomplete, Variable::getValue)
                .containsExactlyInAnyOrder(
                        tuple("regular_value", false, false, "hello"),
                        tuple("sensitive_token", true, true, ""));
    }

    private VariableData buildVariableData(String id, String key, String value, boolean sensitive, String category, boolean hcl) {
        VariableAttributes attributes = new VariableAttributes();
        attributes.setKey(key);
        attributes.setValue(value);
        attributes.setDescription("description-" + key);
        attributes.setSensitive(sensitive);
        attributes.setCategory(category);
        attributes.setHcl(hcl);

        VariableData variableData = new VariableData();
        variableData.setId(id);
        variableData.setType("vars");
        variableData.setAttributes(attributes);
        return variableData;
    }
}