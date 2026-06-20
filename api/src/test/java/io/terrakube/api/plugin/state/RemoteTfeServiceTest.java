package io.terrakube.api.plugin.state;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.security.encryption.EncryptionService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceList;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceModel;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.ArchiveRepository;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.ProjectRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.repository.TemplateRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.ExecutionMode;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteTfeServiceTest {

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final ContentRepository contentRepository = Mockito.mock(ContentRepository.class);
    private final OrganizationRepository organizationRepository = Mockito.mock(OrganizationRepository.class);
    private final WorkspaceRepository workspaceRepository = Mockito.mock(WorkspaceRepository.class);
    private final HistoryRepository historyRepository = Mockito.mock(HistoryRepository.class);
    private final TemplateRepository templateRepository = Mockito.mock(TemplateRepository.class);
    private final ScheduleJobService scheduleJobService = Mockito.mock(ScheduleJobService.class);
    private final StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
    private final StepRepository stepRepository = Mockito.mock(StepRepository.class);
    @SuppressWarnings("rawtypes")
    private final RedisTemplate redisTemplate = Mockito.mock(RedisTemplate.class);
    private final TagRepository tagRepository = Mockito.mock(TagRepository.class);
    private final WorkspaceTagRepository workspaceTagRepository = Mockito.mock(WorkspaceTagRepository.class);
    private final TeamTokenService teamTokenService = Mockito.mock(TeamTokenService.class);
    private final ArchiveRepository archiveRepository = Mockito.mock(ArchiveRepository.class);
    private final AccessRepository accessRepository = Mockito.mock(AccessRepository.class);
    private final EncryptionService encryptionService = Mockito.mock(EncryptionService.class);
    private final AddressRepository addressRepository = Mockito.mock(AddressRepository.class);
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final VariableRepository variableRepository = Mockito.mock(VariableRepository.class);
    private final GlobalVarRepository globalVarRepository = Mockito.mock(GlobalVarRepository.class);
    private final RbacService rbacService = Mockito.mock(RbacService.class);

    @Test
    void listWorkspaceWithSearchNameUsesLoadedWorkspaceEntities() {
        RemoteTfeService service = remoteTfeService();
        JwtAuthenticationToken currentUser = currentUser();
        Organization organization = organization("sample-org");
        Workspace alpha = workspace("alpha", organization);
        Workspace alphaTools = workspace("alpha-tools", organization);
        when(teamTokenService.getCurrentGroups(currentUser)).thenReturn(Collections.emptyList());
        when(workspaceRepository.findWorkspacesByOrganizationNameAndNameStartingWith("sample-org", "alpha"))
                .thenReturn(Optional.of(List.of(alpha, alphaTools)));
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdAsc(any(), anyList()))
                .thenReturn(Optional.empty());

        WorkspaceList result = service.listWorkspace("sample-org", Optional.empty(), Optional.of("alpha"), currentUser);

        assertEquals(2, result.getData().size());
        assertEquals("alpha", result.getData().get(0).getAttributes().get("name"));
        assertEquals("alpha-tools", result.getData().get(1).getAttributes().get("name"));
        verify(workspaceRepository, never()).getByOrganizationNameAndName(anyString(), anyString());
        verify(tagRepository, never()).getReferenceById(any());
    }

    @Test
    void listWorkspaceWithSearchTagsMatchesAllTagsWithoutPerWorkspaceTagLookups() {
        RemoteTfeService service = remoteTfeService();
        JwtAuthenticationToken currentUser = currentUser();
        Organization organization = organization("sample-org");
        Workspace prodAws = workspace("prod-aws", organization);
        Workspace prodOnly = workspace("prod-only", organization);
        Workspace devAws = workspace("dev-aws", organization);
        organization.setWorkspace(List.of(prodAws, prodOnly, devAws));

        Tag prod = tag("prod", organization);
        Tag aws = tag("aws", organization);
        Tag dev = tag("dev", organization);
        prodAws.setWorkspaceTag(List.of(workspaceTag(prod), workspaceTag(aws)));
        prodOnly.setWorkspaceTag(List.of(workspaceTag(prod)));
        devAws.setWorkspaceTag(List.of(workspaceTag(dev), workspaceTag(aws)));

        when(teamTokenService.getCurrentGroups(currentUser)).thenReturn(Collections.emptyList());
        when(organizationRepository.getOrganizationByName("sample-org")).thenReturn(organization);
        when(tagRepository.findByOrganizationName("sample-org")).thenReturn(List.of(prod, aws, dev));
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdAsc(any(), anyList()))
                .thenReturn(Optional.empty());

        WorkspaceList result = service.listWorkspace("sample-org", Optional.of("prod,aws"), Optional.empty(), currentUser);

        assertEquals(1, result.getData().size());
        assertEquals("prod-aws", result.getData().get(0).getAttributes().get("name"));
        verify(tagRepository).findByOrganizationName("sample-org");
        verify(tagRepository, never()).getReferenceById(any());
        verify(workspaceRepository, never()).getByOrganizationNameAndName(anyString(), anyString());
    }

    @Test
    void listWorkspaceKeepsWorkspaceSpecificPermissionChecks() {
        RemoteTfeService service = remoteTfeService();
        JwtAuthenticationToken currentUser = currentUser();
        Organization organization = organization("sample-org");
        Team team = team("developers");
        organization.setTeam(List.of(team));
        Workspace workspace = workspace("restricted", organization);
        when(teamTokenService.getCurrentGroups(currentUser)).thenReturn(List.of("developers"));
        when(workspaceRepository.findWorkspacesByOrganizationNameAndNameStartingWith("sample-org", "restricted"))
                .thenReturn(Optional.of(List.of(workspace)));
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdAsc(any(), anyList()))
                .thenReturn(Optional.empty());
        when(rbacService.canManageWorkspace(team)).thenReturn(false);
        when(rbacService.canManageJob(team)).thenReturn(false);
        when(rbacService.canApproveJob(team)).thenReturn(false);

        WorkspaceList result = service.listWorkspace("sample-org", Optional.empty(), Optional.of("restricted"), currentUser);

        Map<String, Boolean> permissions = permissions(result.getData().get(0));
        assertFalse(permissions.get("can-update"));
        assertFalse(permissions.get("can-manage-tags"));
        assertFalse(permissions.get("can-queue-run"));
        assertFalse(permissions.get("can-queue-apply"));
        assertTrue(permissions.get("can-read-settings"));
        verify(rbacService).canManageWorkspace(team);
        verify(rbacService).canManageJob(team);
        verify(rbacService).canApproveJob(team);
    }

    private RemoteTfeService remoteTfeService() {
        return new RemoteTfeService(jobRepository, contentRepository, organizationRepository, workspaceRepository,
                historyRepository, templateRepository, scheduleJobService, "localhost", storageTypeService,
                stepRepository, redisTemplate, 1, tagRepository, workspaceTagRepository, teamTokenService,
                archiveRepository, accessRepository, encryptionService, addressRepository, projectRepository,
                variableRepository, globalVarRepository, rbacService);
    }

    private JwtAuthenticationToken currentUser() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("iss", "issuer")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private Organization organization(String name) {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName(name);
        organization.setTeam(Collections.emptyList());
        organization.setWorkspace(Collections.emptyList());
        return organization;
    }

    private Workspace workspace(String name, Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName(name);
        workspace.setOrganization(organization);
        workspace.setTerraformVersion("1.6.0");
        workspace.setExecutionMode(ExecutionMode.remote);
        workspace.setAccess(Collections.emptyList());
        workspace.setWorkspaceTag(Collections.emptyList());
        return workspace;
    }

    private Tag tag(String name, Organization organization) {
        Tag tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName(name);
        tag.setOrganization(organization);
        return tag;
    }

    private WorkspaceTag workspaceTag(Tag tag) {
        WorkspaceTag workspaceTag = new WorkspaceTag();
        workspaceTag.setId(UUID.randomUUID());
        workspaceTag.setTagId(tag.getId().toString());
        return workspaceTag;
    }

    private Team team(String name) {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setName(name);
        return team;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> permissions(WorkspaceModel workspaceModel) {
        return (Map<String, Boolean>) workspaceModel.getAttributes().get("permissions");
    }
}
