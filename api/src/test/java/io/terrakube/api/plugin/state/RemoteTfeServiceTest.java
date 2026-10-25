package io.terrakube.api.plugin.state;

import io.terrakube.api.plugin.policy.PolicyApprovalGuard;
import io.terrakube.api.plugin.security.audit.JobApprovalService;
import io.terrakube.api.plugin.state.model.apply.ApplyRunModel;
import io.terrakube.api.plugin.state.model.plan.PlanRunModel;
import io.terrakube.api.plugin.state.model.runs.RunsData;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.template.Template;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.security.encryption.EncryptionService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.state.model.state.StateData;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceData;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceList;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceModel;
import io.terrakube.api.plugin.state.model.workspace.tags.TagBindingList;
import io.terrakube.api.plugin.state.model.workspace.tags.TagBindingModel;
import io.terrakube.api.plugin.state.model.workspace.tags.TagDataList;
import io.terrakube.api.plugin.state.model.workspace.tags.TagModel;
import io.terrakube.api.plugin.logs.StepLogService;
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
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private final JobNotificationTrigger jobNotificationTrigger = Mockito.mock(JobNotificationTrigger.class);

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

        WorkspaceList result = service.listWorkspace("sample-org", query("search[name]", "alpha"), currentUser);

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

        WorkspaceList result = service.listWorkspace("sample-org", query("search[tags]", "prod,aws"), currentUser);

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

        WorkspaceList result = service.listWorkspace("sample-org", query("search[name]", "restricted"), currentUser);

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

    @Test
    void listWorkspaceExposesVersionConstraintAsLatestForTfeCompatibility() {
        RemoteTfeService service = remoteTfeService();
        JwtAuthenticationToken currentUser = currentUser();
        Organization organization = organization("sample-org");
        Workspace constrained = workspace("constrained", organization);
        constrained.setTerraformVersion(">= 1.12.5");
        when(teamTokenService.getCurrentGroups(currentUser)).thenReturn(Collections.emptyList());
        when(workspaceRepository.findWorkspacesByOrganizationNameAndNameStartingWith("sample-org", "constrained"))
                .thenReturn(Optional.of(List.of(constrained)));
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdAsc(any(), anyList()))
                .thenReturn(Optional.empty());

        WorkspaceList result = service.listWorkspace("sample-org", query("search[name]", "constrained"), currentUser);

        assertEquals("latest", result.getData().get(0).getAttributes().get("terraform-version"));
    }

    @Test
    void listWorkspaceKeepsExactVersionUnchanged() {
        RemoteTfeService service = remoteTfeService();
        JwtAuthenticationToken currentUser = currentUser();
        Organization organization = organization("sample-org");
        Workspace exact = workspace("exact", organization);
        when(teamTokenService.getCurrentGroups(currentUser)).thenReturn(Collections.emptyList());
        when(workspaceRepository.findWorkspacesByOrganizationNameAndNameStartingWith("sample-org", "exact"))
                .thenReturn(Optional.of(List.of(exact)));
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdAsc(any(), anyList()))
                .thenReturn(Optional.empty());

        WorkspaceList result = service.listWorkspace("sample-org", query("search[name]", "exact"), currentUser);

        assertEquals("1.6.0", result.getData().get(0).getAttributes().get("terraform-version"));
    }

    // The CLI renders `tofu plan` output straight from the bytes this returns. The executor now
    // writes a diagnostic's full rendering (header + `on <file> line <n>` + snippet + detail) to
    // the job's step-100 log stream as several lines; getPlanLogs must hand all of them back, not
    // drop everything after the "Error:" header, and the offset/limit slice must not chop the
    // block when a page big enough to hold it is requested.
    @Test
    @SuppressWarnings("unchecked")
    void planLogsReturnTheFullMultiLineDiagnosticBlock() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(123);
        Step planStep = new Step();
        planStep.setId(UUID.randomUUID());
        planStep.setStepNumber(100);
        job.setStep(List.of(planStep));

        when(encryptionService.decrypt("encrypted-plan-id")).thenReturn("123");
        when(jobRepository.findById(123)).thenReturn(Optional.of(job));

        String executorConsole = String.join("\n",
                "Plan: 0 to add, 0 to change, 0 to destroy.",
                "",
                "Error: Unsupported attribute",
                "",
                "  on main.tf line 12, in resource \"aws_instance\" \"web\":",
                "  12:   subnet = aws_subnet.main.identifier",
                "",
                "This object has no argument, nested block, or exported attribute named \"identifier\".");

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class)))
                .thenReturn(List.of(MapRecord.create("123", Map.of("output", executorConsole))));

        String logs = new String(service.getPlanLogs("encrypted-plan-id", 0, 500_000), StandardCharsets.UTF_8);

        assertTrue(logs.contains("on main.tf line 12, in resource \"aws_instance\" \"web\":"), logs);
        assertTrue(logs.contains("12:   subnet = aws_subnet.main.identifier"), logs);
        assertTrue(logs.contains(
                "This object has no argument, nested block, or exported attribute named \"identifier\"."), logs);
    }

    // getPlanLogs/getApplyLogs must pass exactly one StreamOffset for the job id. The second
    // offset they used to pass was StreamOffset.latest(), i.e. "$", which contributes nothing to
    // a historical read and only duplicates the stream key in the XREAD. Redis and Valkey tolerate
    // the repeated key; other Redis-protocol servers reject it outright, and the resulting
    // exception left the caller with empty logs. Regression coverage: a test only asserting the
    // returned log text would still pass against the old two-offset call under a lenient mock,
    // so this pins the call shape instead.
    @Test
    @SuppressWarnings("unchecked")
    void getPlanLogsReadsWithExactlyOneStreamOffsetForTheJobId() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(456);
        Step planStep = new Step();
        planStep.setId(UUID.randomUUID());
        planStep.setStepNumber(100);
        job.setStep(List.of(planStep));

        when(encryptionService.decrypt("encrypted-plan-id")).thenReturn("456");
        when(jobRepository.findById(456)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        ArgumentCaptor<StreamOffset[]> offsetsCaptor = ArgumentCaptor.forClass(StreamOffset[].class);
        when(streamOperations.read(offsetsCaptor.capture()))
                .thenReturn(List.of(MapRecord.create("456", Map.of("output", "line 1"))));

        service.getPlanLogs("encrypted-plan-id", 0, 500);

        StreamOffset[] offsets = offsetsCaptor.getValue();
        assertEquals(1, offsets.length);
        assertEquals("456", offsets[0].getKey());
    }

    // Same bug, same fix, in the sibling method: getApplyLogs must also pass exactly one
    // StreamOffset for the job id.
    @Test
    @SuppressWarnings("unchecked")
    void getApplyLogsReadsWithExactlyOneStreamOffsetForTheJobId() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(789);
        Step applyStep = new Step();
        applyStep.setId(UUID.randomUUID());
        applyStep.setStepNumber(200);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-id")).thenReturn("789");
        when(jobRepository.findById(789)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        ArgumentCaptor<StreamOffset[]> offsetsCaptor = ArgumentCaptor.forClass(StreamOffset[].class);
        when(streamOperations.read(offsetsCaptor.capture()))
                .thenReturn(List.of(MapRecord.create("789", Map.of("output", "line 1"))));

        service.getApplyLogs("encrypted-apply-id", 0, 500);

        StreamOffset[] offsets = offsetsCaptor.getValue();
        assertEquals(1, offsets.length);
        assertEquals("789", offsets[0].getKey());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApplyLogsFiltersByStepId() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(800);
        UUID applyStepId = UUID.randomUUID();
        Step applyStep = new Step();
        applyStep.setId(applyStepId);
        applyStep.setStepNumber(200);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-id-2")).thenReturn("800");
        when(jobRepository.findById(800)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class)))
                .thenReturn(List.of(
                        MapRecord.create("800", Map.of("stepId", UUID.randomUUID().toString(), "output", "plan line")),
                        MapRecord.create("800", Map.of("stepId", applyStepId.toString(), "output", "apply line 1")),
                        MapRecord.create("800", Map.of("stepId", applyStepId.toString(), "output", "Apply complete!"))
                ));

        String logs = new String(service.getApplyLogs("encrypted-apply-id-2", 0, 500), StandardCharsets.UTF_8);

        assertFalse(logs.contains("plan line"), logs);
        assertTrue(logs.contains("apply line 1"), logs);
        assertTrue(logs.contains("Apply complete!"), logs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApplyLogsFallsBackToStorageWhenRedisIsEmpty() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(801);
        Organization org = organization("sample-org");
        job.setOrganization(org);
        UUID applyStepId = UUID.randomUUID();
        Step applyStep = new Step();
        applyStep.setId(applyStepId);
        applyStep.setStepNumber(200);
        applyStep.setStatus(JobStatus.completed);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-id-3")).thenReturn("801");
        when(jobRepository.findById(801)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class))).thenReturn(Collections.emptyList());

        when(storageTypeService.getStepOutput(org.getId().toString(), "801", applyStepId.toString()))
                .thenReturn("Apply output from storage\nApply complete!\n".getBytes(StandardCharsets.UTF_8));

        String logs = new String(service.getApplyLogs("encrypted-apply-id-3", 0, 500), StandardCharsets.UTF_8);

        assertTrue(logs.contains("Apply output from storage"), logs);
        assertTrue(logs.contains("Apply complete!"), logs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPlanLogsFallsBackToStorageWhenRedisIsEmpty() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(802);
        Organization org = organization("sample-org");
        job.setOrganization(org);
        UUID planStepId = UUID.randomUUID();
        Step planStep = new Step();
        planStep.setId(planStepId);
        planStep.setStepNumber(100);
        planStep.setStatus(JobStatus.completed);
        job.setStep(List.of(planStep));

        when(encryptionService.decrypt("encrypted-plan-id-1")).thenReturn("802");
        when(jobRepository.findById(802)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class))).thenReturn(Collections.emptyList());

        when(storageTypeService.getStepOutput(org.getId().toString(), "802", planStepId.toString()))
                .thenReturn("Plan output from storage\nPlan: 1 to add\n".getBytes(StandardCharsets.UTF_8));

        String logs = new String(service.getPlanLogs("encrypted-plan-id-1", 0, 500), StandardCharsets.UTF_8);

        assertTrue(logs.contains("Plan output from storage"), logs);
        assertTrue(logs.contains("Plan: 1 to add"), logs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void planStepWithNoChangesStatusFallsBackToStorageWhenRedisIsEmpty() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(8021);
        Organization org = organization("sample-org");
        job.setOrganization(org);
        UUID planStepId = UUID.randomUUID();
        Step planStep = new Step();
        planStep.setId(planStepId);
        planStep.setStepNumber(100);
        planStep.setStatus(JobStatus.noChanges);
        job.setStep(List.of(planStep));

        when(encryptionService.decrypt("encrypted-plan-nochanges")).thenReturn("8021");
        when(jobRepository.findById(8021)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class))).thenReturn(Collections.emptyList());

        when(storageTypeService.getStepOutput(org.getId().toString(), "8021", planStepId.toString()))
                .thenReturn("No changes. Your infrastructure matches the configuration.\n".getBytes(StandardCharsets.UTF_8));

        String logs = new String(service.getPlanLogs("encrypted-plan-nochanges", 0, 500), StandardCharsets.UTF_8);

        assertTrue(logs.contains("No changes. Your infrastructure matches the configuration."), logs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runningStepWithEmptyStreamDoesNotTouchStorage() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(803);
        Organization org = organization("sample-org");
        job.setOrganization(org);
        UUID applyStepId = UUID.randomUUID();
        Step applyStep = new Step();
        applyStep.setId(applyStepId);
        applyStep.setStepNumber(200);
        applyStep.setStatus(JobStatus.running);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-running")).thenReturn("803");
        when(jobRepository.findById(803)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class))).thenReturn(Collections.emptyList());

        byte[] logs = service.getApplyLogs("encrypted-apply-running", 0, 500);

        assertEquals(0, logs.length);
        verify(storageTypeService, never()).getStepOutput(any(), any(), any());
        verify(storageTypeService, never()).getStepOutputStream(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStepLogsOffsetPastEndReturnsEmptyArray() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(804);
        UUID applyStepId = UUID.randomUUID();
        Step applyStep = new Step();
        applyStep.setId(applyStepId);
        applyStep.setStepNumber(200);
        applyStep.setStatus(JobStatus.completed);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-804")).thenReturn("804");
        when(jobRepository.findById(804)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class)))
                .thenReturn(List.of(
                        MapRecord.create("804", Map.of("stepId", applyStepId.toString(), "output", "short output"))
                ));

        byte[] logs = service.getApplyLogs("encrypted-apply-804", 1000, 500);

        assertEquals(0, logs.length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStepLogsIncludesRecordsWithoutStepIdForCompatibility() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(805);
        UUID applyStepId = UUID.randomUUID();
        Step applyStep = new Step();
        applyStep.setId(applyStepId);
        applyStep.setStepNumber(200);
        applyStep.setStatus(JobStatus.completed);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-805")).thenReturn("805");
        when(jobRepository.findById(805)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        Map<String, String> dataWithoutStepId = new HashMap<>();
        dataWithoutStepId.put("output", "legacy line without stepId");
        when(streamOperations.read(any(StreamOffset.class)))
                .thenReturn(List.of(
                        MapRecord.create("805", dataWithoutStepId)
                ));

        String logs = new String(service.getApplyLogs("encrypted-apply-805", 0, 500), StandardCharsets.UTF_8);

        assertTrue(logs.contains("legacy line without stepId"), logs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStepLogsByteOffsetHandlesMultiByteUtf8WithoutDrift() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(806);
        UUID applyStepId = UUID.randomUUID();
        Step applyStep = new Step();
        applyStep.setId(applyStepId);
        applyStep.setStepNumber(200);
        applyStep.setStatus(JobStatus.completed);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-806")).thenReturn("806");
        when(jobRepository.findById(806)).thenReturn(Optional.of(job));

        String line = "✓ done";
        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class)))
                .thenReturn(List.of(
                        MapRecord.create("806", Map.of("stepId", applyStepId.toString(), "output", line))
                ));

        byte[] sliced = service.getApplyLogs("encrypted-apply-806", 3, 50);
        assertEquals(" done\n", new String(sliced, StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApplyLogsUsesStepLogServiceWhenPresent() {
        RemoteTfeService service = remoteTfeService();
        StepLogService stepLogService = Mockito.mock(StepLogService.class);
        service.setStepLogService(stepLogService);

        Job job = new Job();
        job.setId(807);
        Organization org = organization("sample-org");
        job.setOrganization(org);
        UUID applyStepId = UUID.randomUUID();
        Step applyStep = new Step();
        applyStep.setId(applyStepId);
        applyStep.setStepNumber(200);
        applyStep.setStatus(JobStatus.completed);
        job.setStep(List.of(applyStep));

        when(encryptionService.decrypt("encrypted-apply-807")).thenReturn("807");
        when(jobRepository.findById(807)).thenReturn(Optional.of(job));

        StreamOperations<String, String, String> streamOperations = Mockito.mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.read(any(StreamOffset.class))).thenReturn(Collections.emptyList());

        byte[] cachedBody = "Cached apply log from StepLogService\n".getBytes(StandardCharsets.UTF_8);
        when(stepLogService.resolve(org.getId().toString(), "807", applyStepId.toString()))
                .thenReturn(StepLogService.StepLog.cached(cachedBody));

        byte[] logs = service.getApplyLogs("encrypted-apply-807", 0, 500);

        assertEquals("Cached apply log from StepLogService\n", new String(logs, StandardCharsets.UTF_8));
    }

    @Test
    void getRunDoesNotIncludeApplyWhenIncludeContainsPartialWord() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(901);
        Organization org = organization("sample-org");
        Workspace workspace = workspace("sample", org);
        job.setOrganization(org);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.running);

        when(jobRepository.getReferenceById(901)).thenReturn(job);

        RunsData runData = service.getRun(901, "workspace,applies", null);

        assertNotNull(runData);
        assertNull(runData.getIncluded(), "Included should be null or not contain ApplyRunModel when 'applies' is passed");
    }

    @Test
    void getRunIncludesApplyAndPlanInIncludedSectionWhenRequested() {
        RemoteTfeService service = remoteTfeService();

        Job job = new Job();
        job.setId(900);
        Organization org = organization("sample-org");
        Workspace workspace = workspace("sample", org);
        job.setOrganization(org);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.running);

        Step planStep = new Step();
        planStep.setId(UUID.randomUUID());
        planStep.setStepNumber(100);
        planStep.setStatus(JobStatus.completed);

        Step applyStep = new Step();
        applyStep.setId(UUID.randomUUID());
        applyStep.setStepNumber(200);
        applyStep.setStatus(JobStatus.running);

        job.setStep(List.of(planStep, applyStep));

        when(jobRepository.getReferenceById(900)).thenReturn(job);
        when(encryptionService.encrypt("900")).thenReturn("encrypted-900");

        RunsData runData = service.getRun(900, "plan,apply", null);

        assertNotNull(runData);
        assertNotNull(runData.getIncluded());
        assertEquals(2, runData.getIncluded().size());
        boolean hasApply = runData.getIncluded().stream().anyMatch(res -> res instanceof ApplyRunModel);
        boolean hasPlan = runData.getIncluded().stream().anyMatch(res -> res instanceof PlanRunModel);
        assertTrue(hasApply, "Should include apply model");
        assertTrue(hasPlan, "Should include plan model");
    }

    @ParameterizedTest
    @CsvSource({"false, auditor@example.com", "true, auditor@example.com", "false,", "true,"})
    void cliApprovalRecordsActorAndDoesNotOverwriteOnRetry(boolean planOnly, String auditor) {
        RemoteTfeService service = Mockito.spy(remoteTfeService());
        ReflectionTestUtils.setField(service, "jobApprovalService",
                new JobApprovalService(() -> Optional.ofNullable(auditor)));
        JwtAuthenticationToken user = new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none").issuer("issuer").subject("cli-user-id").build());
        Organization org = organization("sample-org");
        Team approvers = team("approvers");
        org.setTeam(List.of(approvers));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("approvers"));
        when(rbacService.canApproveJob(approvers)).thenReturn(true);
        Job job = new Job();
        job.setId(3554);
        job.setOrganization(org);
        job.setWorkspace(workspace("sample", org));
        job.setStatus(planOnly ? JobStatus.completed
                : JobStatus.waitingApproval);
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(planOnly ? 100 : 150);
        step.setStatus(JobStatus.pending);
        job.setStep(new ArrayList<>(List.of(step)));
        when(jobRepository.getReferenceById(3554)).thenReturn(job);
        when(jobRepository.save(job)).thenReturn(job);
        when(stepRepository.save(any(Step.class))).thenAnswer(call -> call.getArgument(0));
        when(templateRepository.getByOrganizationNameAndName("sample-org", "Terraform-Plan/Apply-Cli"))
                .thenReturn(new Template());
        Mockito.doReturn(null).when(service).getRun(3554, null);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.runApply(3554, user);
            assertEquals(auditor == null ? "cli-user-id" : auditor, job.getApprovedBy());
            assertNotNull(job.getApprovedAt());
            var approvedAt = job.getApprovedAt();
            if (planOnly) {
                // A fresh request also loads the approval step created by the first request.
                Step approvalStep = new Step();
                approvalStep.setStepNumber(150);
                approvalStep.setStatus(JobStatus.pending);
                job.getStep().add(approvalStep);
            }
            service.runApply(3554, user);
            assertEquals(approvedAt, job.getApprovedAt());
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void cliApprovalIsRefusedWhilePolicyBlocksTheJob(boolean planOnly) {
        RemoteTfeService service = Mockito.spy(remoteTfeService());
        PolicyApprovalGuard guard = Mockito.mock(PolicyApprovalGuard.class);
        service.setPolicyApprovalGuard(guard);
        JwtAuthenticationToken user = new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none").issuer("issuer").subject("cli-user-id").build());
        Organization org = organization("sample-org");
        Team approvers = team("approvers");
        org.setTeam(List.of(approvers));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("approvers"));
        when(rbacService.canApproveJob(approvers)).thenReturn(true);
        Job job = new Job();
        job.setId(3554);
        job.setOrganization(org);
        job.setWorkspace(workspace("sample", org));
        job.setStatus(planOnly ? JobStatus.completed : JobStatus.waitingApproval);
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(planOnly ? 100 : 150);
        step.setStatus(JobStatus.pending);
        job.setStep(new ArrayList<>(List.of(step)));
        when(jobRepository.getReferenceById(3554)).thenReturn(job);
        when(guard.blockingReason(job)).thenReturn(Optional.of("Job 3554 cannot be approved: policy"));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> service.runApply(3554, user));

        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertNull(job.getApprovedAt());
        verify(stepRepository, never()).save(any(Step.class));
        verify(jobRepository, never()).save(any(Job.class));
    }

    @ParameterizedTest
    @CsvSource({"pending", "queue", "running"})
    void cliApprovalIsRefusedWhileTheRunIsStillPlanning(JobStatus status) {
        RemoteTfeService service = Mockito.spy(remoteTfeService());
        PolicyApprovalGuard guard = Mockito.mock(PolicyApprovalGuard.class);
        service.setPolicyApprovalGuard(guard);
        Job job = jobWaitingAtTheApprovalStep(status);
        JwtAuthenticationToken user = approverOf(job);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> service.runApply(3554, user));

        // No policy result exists yet while the plan runs, so the guard could not block anything.
        assertEquals(HttpStatus.CONFLICT, thrown.getStatusCode());
        assertNull(job.getApprovedAt());
        verify(guard, never()).blockingReason(any());
        verify(stepRepository, never()).save(any(Step.class));
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void cliApprovalFailsClosedWhenThereIsNoPolicyGuard() {
        RemoteTfeService service = Mockito.spy(remoteTfeServiceWithoutPolicyGuard());
        Job job = jobWaitingAtTheApprovalStep(JobStatus.waitingApproval);
        JwtAuthenticationToken user = approverOf(job);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () -> service.runApply(3554, user));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, thrown.getStatusCode());
        assertNull(job.getApprovedAt());
        verify(jobRepository, never()).save(any(Job.class));
    }

    private Job jobWaitingAtTheApprovalStep(JobStatus status) {
        Organization org = organization("sample-org");
        Team approvers = team("approvers");
        org.setTeam(List.of(approvers));
        Job job = new Job();
        job.setId(3554);
        job.setOrganization(org);
        job.setWorkspace(workspace("sample", org));
        job.setStatus(status);
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(150);
        step.setStatus(JobStatus.pending);
        job.setStep(new ArrayList<>(List.of(step)));
        when(jobRepository.getReferenceById(3554)).thenReturn(job);
        return job;
    }

    private JwtAuthenticationToken approverOf(Job job) {
        JwtAuthenticationToken user = new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none").issuer("issuer").subject("cli-user-id").build());
        Team approvers = job.getOrganization().getTeam().get(0);
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("approvers"));
        when(rbacService.canApproveJob(approvers)).thenReturn(true);
        return user;
    }

    // --- key/value tags (tag bindings) ---

    @Test
    void listWorkspaceWithTaggedFilterMatchesKeyAndValue() {
        TagFixture fixture = new TagFixture();
        Workspace prod = fixture.workspace("app-prod", "env", "prod");
        fixture.workspace("app-dev", "env", "dev");
        fixture.workspace("app-untagged");

        // The CLI repeats the keys of key/value tags in search[tags]
        WorkspaceList result = fixture.service.listWorkspace("sample-org", query(
                "filter[tagged][0][key]", "env", "filter[tagged][0][value]", "prod", "search[tags]", "env"),
                fixture.currentUser);

        assertEquals(List.of(prod.getName()), names(result));
    }

    @Test
    void listWorkspaceWithTaggedFilterWithoutValueMatchesAnyValueOfTheKey() {
        TagFixture fixture = new TagFixture();
        fixture.workspace("app-prod", "env", "prod");
        fixture.workspace("app-keyonly", "env", null);
        fixture.workspace("app-other", "team", "infra");

        WorkspaceList result = fixture.service.listWorkspace("sample-org", query(
                "filter[tagged][0][key]", "env", "filter[tagged][0][value]", ""), fixture.currentUser);

        assertEquals(List.of("app-keyonly", "app-prod"), names(result));
    }

    @Test
    void listWorkspaceWithTaggedFilterRequiresEveryPairWhateverTheIndexes() {
        TagFixture fixture = new TagFixture();
        Workspace match = fixture.workspace("match", "env", "prod", "team", "infra");
        fixture.workspace("wrong-team", "env", "prod", "team", "apps");
        fixture.workspace("env-only", "env", "prod");

        // Go map iteration order: indexes arrive unordered and not necessarily contiguous
        WorkspaceList result = fixture.service.listWorkspace("sample-org", query(
                "filter[tagged][3][value]", "infra", "filter[tagged][0][key]", "env",
                "filter[tagged][3][key]", "team", "filter[tagged][0][value]", "prod"), fixture.currentUser);

        assertEquals(List.of(match.getName()), names(result));
    }

    @Test
    void listWorkspaceAppliesAllFiltersTogetherWithoutDuplicates() {
        TagFixture fixture = new TagFixture();
        Project project = new Project();
        project.setId(UUID.randomUUID());
        Workspace match = fixture.workspace("app-prod", "env", "prod", "app", null);
        match.setProject(project);
        Workspace otherProject = fixture.workspace("app-prod-2", "env", "prod", "app", null);
        Workspace otherName = fixture.workspace("web-prod", "env", "prod", "app", null);
        otherName.setProject(project);
        when(workspaceRepository.findWorkspacesByOrganizationNameAndNameStartingWith("sample-org", "app"))
                .thenReturn(Optional.of(List.of(match, otherProject)));

        WorkspaceList result = fixture.service.listWorkspace("sample-org", query(
                "search[name]", "app", "search[tags]", "app,env", "filter[tagged][0][key]", "env",
                "filter[tagged][0][value]", "prod", "filter[project][id]", project.getId().toString()),
                fixture.currentUser);

        assertEquals(List.of(match.getName()), names(result));
    }

    @Test
    void listWorkspaceWithoutAnyFilterReturnsNothing() {
        TagFixture fixture = new TagFixture();
        fixture.workspace("app-prod", "env", "prod");

        WorkspaceList result = fixture.service.listWorkspace("sample-org", query(), fixture.currentUser);

        assertTrue(result.getData().isEmpty());
        assertEquals(0, pagination(result).get("total-count"));
    }

    @Test
    void listWorkspacePagesMatchesWithPageSize() {
        TagFixture fixture = new TagFixture();
        fixture.workspace("app-a", "env", "prod");
        fixture.workspace("app-b", "env", "prod");
        fixture.workspace("app-c", "env", "prod");

        WorkspaceList firstPage = fixture.service.listWorkspace("sample-org",
                query("search[tags]", "env", "page[size]", "2"), fixture.currentUser);
        WorkspaceList secondPage = fixture.service.listWorkspace("sample-org",
                query("search[tags]", "env", "page[size]", "2", "page[number]", "2"), fixture.currentUser);
        WorkspaceList pastTheEnd = fixture.service.listWorkspace("sample-org",
                query("search[tags]", "env", "page[size]", "2", "page[number]", "3"), fixture.currentUser);

        assertEquals(List.of("app-a", "app-b"), names(firstPage));
        assertEquals(paginationOf(1, 2, null, 2, 2, 3), pagination(firstPage));
        assertEquals(List.of("app-c"), names(secondPage));
        assertEquals(paginationOf(2, 2, 1, null, 2, 3), pagination(secondPage));
        assertTrue(pastTheEnd.getData().isEmpty());
        assertEquals(paginationOf(3, 2, 2, null, 2, 3), pagination(pastTheEnd));
    }

    @Test
    void listWorkspaceUsesTwentyWorkspacesPerPageByDefault() {
        TagFixture fixture = new TagFixture();
        for (int i = 1; i <= 21; i++) {
            fixture.workspace(String.format("app-%02d", i), "env", "prod");
        }

        WorkspaceList firstPage = fixture.service.listWorkspace("sample-org", query("search[tags]", "env"),
                fixture.currentUser);

        assertEquals(20, firstPage.getData().size());
        assertEquals(paginationOf(1, 20, null, 2, 2, 21), pagination(firstPage));
    }

    @Test
    @SuppressWarnings("unchecked")
    void workspaceTagNamesIncludeKeysWithAndWithoutValue() {
        TagFixture fixture = new TagFixture();
        fixture.workspace("app", "env", "prod", "legacy", null);

        WorkspaceList result = fixture.service.listWorkspace("sample-org", query("search[tags]", "legacy"),
                fixture.currentUser);

        assertEquals(List.of("env", "legacy"), result.getData().get(0).getAttributes().get("tag-names"));
    }

    @Test
    void listTagBindingsExposesKeyOnlyTagsWithEmptyValue() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app", "env", "prod", "legacy", null);

        TagBindingList bindings = fixture.service.listTagBindings(workspace.getId().toString(), fixture.currentUser);

        assertEquals(List.of(Map.of("key", "env", "value", "prod"), Map.of("key", "legacy", "value", "")),
                bindings.getData().stream().map(TagBindingModel::getAttributes).toList());
        assertEquals("tag-bindings", bindings.getData().get(0).getType());
    }

    @Test
    void listTagBindingsOfUnknownWorkspaceReturnsNull() {
        TagFixture fixture = new TagFixture();

        assertNull(fixture.service.listTagBindings(UUID.randomUUID().toString(), fixture.currentUser));
    }

    @Test
    void listTagBindingsWithoutOrganizationAccessIsForbidden() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app", "env", "prod");
        when(teamTokenService.getCurrentGroups(fixture.currentUser)).thenReturn(List.of("outsiders"));

        assertThrows(AccessDeniedException.class,
                () -> fixture.service.listTagBindings(workspace.getId().toString(), fixture.currentUser));
    }

    @Test
    void updateTagBindingsUpsertsByKeyAndKeepsOtherTags() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app", "env", "dev", "owner", "alice");
        fixture.tag("region");

        TagBindingList result = fixture.service.updateTagBindings(workspace.getId().toString(),
                bindings("env", "prod", "region", "eu", "team", null), fixture.currentUser);

        assertEquals(List.of(Map.of("key", "env", "value", "prod"), Map.of("key", "owner", "value", "alice"),
                        Map.of("key", "region", "value", "eu"), Map.of("key", "team", "value", "")),
                result.getData().stream().map(TagBindingModel::getAttributes).toList());
        assertEquals(4, workspace.getWorkspaceTag().size());
        verify(workspaceTagRepository, never()).delete(any());
        verify(workspaceTagRepository, never()).deleteByWorkspace(any());
        // "team" is a new organization key
        verify(tagRepository).save(Mockito.argThat(tag -> "team".equals(tag.getName())));
    }

    @Test
    void updateTagBindingsWithoutManagePermissionIsForbidden() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app", "env", "dev");
        when(rbacService.canManageWorkspace(fixture.team)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> fixture.service.updateTagBindings(
                workspace.getId().toString(), bindings("env", "prod"), fixture.currentUser));
        verify(workspaceTagRepository, never()).save(any());
    }

    @Test
    void updateTagBindingsRejectsKeysAndValuesTooLongForTheDatabase() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app");

        assertThrows(IllegalArgumentException.class, () -> fixture.service.updateTagBindings(
                workspace.getId().toString(), bindings("k".repeat(129), "v"), fixture.currentUser));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.updateTagBindings(
                workspace.getId().toString(), bindings("env", "v".repeat(257)), fixture.currentUser));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.updateTagBindings(
                workspace.getId().toString(), bindings("", "v"), fixture.currentUser));
        verify(workspaceTagRepository, never()).save(any());
    }

    @Test
    void updateTagBindingsAcceptsTheHcpTerraformMaximumLengths() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app");

        TagBindingList result = fixture.service.updateTagBindings(workspace.getId().toString(),
                bindings("k".repeat(128), "v".repeat(256)), fixture.currentUser);

        assertEquals(List.of(Map.of("key", "k".repeat(128), "value", "v".repeat(256))),
                result.getData().stream().map(TagBindingModel::getAttributes).toList());
    }

    @Test
    void updateTagBindingsRejectsMoreThanTenTagsCountingExistingKeysOnce() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app", keyValues(9));

        // 9 existing keys: updating one of them and adding one new key reaches exactly 10
        TagBindingList result = fixture.service.updateTagBindings(workspace.getId().toString(),
                bindings("key-1", "changed", "key-10", "v"), fixture.currentUser);
        assertEquals(10, result.getData().size());

        // An 11th key is rejected before anything is written
        Mockito.clearInvocations(workspaceTagRepository);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fixture.service.updateTagBindings(workspace.getId().toString(),
                        bindings("key-1", "again", "key-11", "v"), fixture.currentUser));
        assertTrue(error.getMessage().contains("at most 10 tags"));
        verify(workspaceTagRepository, never()).save(any());
    }

    @Test
    void updateTagBindingsLetsAWorkspaceAlreadyAboveTheLimitUpdateItsKeys() {
        TagFixture fixture = new TagFixture();
        // More than 10 tags can already exist, added from the UI before the limit
        Workspace workspace = fixture.workspace("app", keyValues(12));

        TagBindingList result = fixture.service.updateTagBindings(workspace.getId().toString(),
                bindings("key-1", "changed"), fixture.currentUser);
        assertEquals(12, result.getData().size());

        assertThrows(IllegalArgumentException.class, () -> fixture.service.updateTagBindings(
                workspace.getId().toString(), bindings("key-13", "v"), fixture.currentUser));
    }

    @Test
    void legacyTagsApiDoesNotOverwriteTheValueOfAKeyValueTag() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app", "env", "prod");
        when(workspaceRepository.getReferenceById(workspace.getId())).thenReturn(workspace);
        TagDataList tags = new TagDataList();
        tags.setData(List.of(tagModel("env"), tagModel("app")));

        fixture.service.updateWorkspaceTags(workspace.getId().toString(), tags, fixture.currentUser);

        TagBindingList bindings = fixture.service.listTagBindings(workspace.getId().toString(), fixture.currentUser);
        assertEquals(List.of(Map.of("key", "app", "value", ""), Map.of("key", "env", "value", "prod")),
                bindings.getData().stream().map(TagBindingModel::getAttributes).toList());
    }

    // go-tfe marshals a tag name with omitempty, so a client attaching an existing tag sends its id alone.
    // Such a request was accepted before key/value tags and must keep working.
    @Test
    void legacyTagsApiIgnoresTagsSentWithoutAName() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app", "env", "prod");
        when(workspaceRepository.getReferenceById(workspace.getId())).thenReturn(workspace);
        TagModel withoutName = new TagModel();
        withoutName.setType("tags");
        withoutName.setId(UUID.randomUUID().toString());
        TagDataList tags = new TagDataList();
        tags.setData(List.of(withoutName, tagModel("app")));

        assertTrue(fixture.service.updateWorkspaceTags(workspace.getId().toString(), tags, fixture.currentUser));

        TagBindingList bindings = fixture.service.listTagBindings(workspace.getId().toString(), fixture.currentUser);
        assertEquals(List.of(Map.of("key", "app", "value", ""), Map.of("key", "env", "value", "prod")),
                bindings.getData().stream().map(TagBindingModel::getAttributes).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createWorkspaceAppliesTagsAndTagBindings() {
        TagFixture fixture = new TagFixture();
        AtomicReference<Workspace> saved = new AtomicReference<>();
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace created = invocation.getArgument(0);
            created.setId(UUID.randomUUID());
            created.setAccess(Collections.emptyList());
            saved.set(created);
            return created;
        });
        when(workspaceRepository.getByOrganizationNameAndName("sample-org", "created"))
                .thenAnswer(invocation -> saved.get());
        when(workspaceRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        io.terrakube.api.plugin.state.model.workspace.Relationships relationships =
                new io.terrakube.api.plugin.state.model.workspace.Relationships();
        TagDataList tags = new TagDataList();
        tags.setData(List.of(tagModel("legacy")));
        relationships.setTags(tags);
        relationships.setTagBindings(bindings("env", "prod"));
        WorkspaceModel model = new WorkspaceModel();
        model.setAttributes(new HashMap<>(Map.of("name", "created", "terraform-version", "1.9.0")));
        model.setRelationships(relationships);
        WorkspaceData request = new WorkspaceData();
        request.setData(model);

        WorkspaceData response = fixture.service.createWorkspace("sample-org", request, fixture.currentUser);

        assertEquals(List.of("env", "legacy"), response.getData().getAttributes().get("tag-names"));
        TagBindingList bindings = fixture.service.listTagBindings(response.getData().getId(), fixture.currentUser);
        assertEquals(List.of(Map.of("key", "env", "value", "prod"), Map.of("key", "legacy", "value", "")),
                bindings.getData().stream().map(TagBindingModel::getAttributes).toList());
        assertEquals(ExecutionMode.remote, saved.get().getExecutionMode());
        // A single workspace resolves only its own tags, not every tag of the organization
        verify(tagRepository, never()).findByOrganizationName(anyString());
    }

    @Test
    void createWorkspaceSetsExecutionMode() {
        TagFixture fixture = new TagFixture();
        AtomicReference<Workspace> saved = new AtomicReference<>();
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace created = invocation.getArgument(0);
            created.setId(UUID.randomUUID());
            created.setAccess(Collections.emptyList());
            saved.set(created);
            return created;
        });
        when(workspaceRepository.getByOrganizationNameAndName("sample-org", "local-ws"))
                .thenAnswer(invocation -> saved.get());
        when(workspaceRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));

        WorkspaceModel model = new WorkspaceModel();
        model.setAttributes(new HashMap<>(Map.of("name", "local-ws", "execution-mode", "local")));
        WorkspaceData request = new WorkspaceData();
        request.setData(model);

        fixture.service.createWorkspace("sample-org", request, fixture.currentUser);
        assertEquals(ExecutionMode.local, saved.get().getExecutionMode());
    }

    @Test
    void createWorkspaceFallsBackToOrganizationExecutionModeWhenOmittedOrUnknown() {
        TagFixture fixture = new TagFixture();
        fixture.organization.setExecutionMode(ExecutionMode.local);
        Map<String, Workspace> workspaces = new HashMap<>();
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace created = invocation.getArgument(0);
            created.setId(UUID.randomUUID());
            created.setAccess(Collections.emptyList());
            workspaces.put(created.getName(), created);
            return created;
        });
        when(workspaceRepository.getByOrganizationNameAndName(Mockito.eq("sample-org"), anyString()))
                .thenAnswer(invocation -> workspaces.get(invocation.getArgument(1, String.class)));
        when(workspaceRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> workspaces.values().stream()
                        .filter(ws -> ws.getId().equals(invocation.getArgument(0)))
                        .findFirst());

        // 1. Missing execution-mode attribute falls back to organization default
        WorkspaceModel missingAttrModel = new WorkspaceModel();
        missingAttrModel.setAttributes(new HashMap<>(Map.of("name", "omitted-mode-ws")));
        WorkspaceData missingRequest = new WorkspaceData();
        missingRequest.setData(missingAttrModel);

        fixture.service.createWorkspace("sample-org", missingRequest, fixture.currentUser);
        assertEquals(ExecutionMode.local, workspaces.get("omitted-mode-ws").getExecutionMode());

        // 2. Unknown execution-mode attribute (e.g. TFE "agent") logs warning and falls back to organization default
        WorkspaceModel unknownAttrModel = new WorkspaceModel();
        unknownAttrModel.setAttributes(new HashMap<>(Map.of("name", "unknown-mode-ws", "execution-mode", "agent")));
        WorkspaceData unknownRequest = new WorkspaceData();
        unknownRequest.setData(unknownAttrModel);

        fixture.service.createWorkspace("sample-org", unknownRequest, fixture.currentUser);
        assertEquals(ExecutionMode.local, workspaces.get("unknown-mode-ws").getExecutionMode());

        // 3. Explicit execution-mode ("remote") wins over organization default ("local")
        WorkspaceModel explicitAttrModel = new WorkspaceModel();
        explicitAttrModel.setAttributes(new HashMap<>(Map.of("name", "explicit-mode-ws", "execution-mode", "remote")));
        WorkspaceData explicitRequest = new WorkspaceData();
        explicitRequest.setData(explicitAttrModel);

        fixture.service.createWorkspace("sample-org", explicitRequest, fixture.currentUser);
        assertEquals(ExecutionMode.remote, workspaces.get("explicit-mode-ws").getExecutionMode());
    }

    @Test
    void createWorkspaceRejectsMoreThanTenTagsAcrossTagsAndTagBindings() {
        TagFixture fixture = new TagFixture();
        io.terrakube.api.plugin.state.model.workspace.Relationships relationships =
                new io.terrakube.api.plugin.state.model.workspace.Relationships();
        TagDataList tags = new TagDataList();
        tags.setData(List.of(tagModel("t1"), tagModel("t2"), tagModel("t3"), tagModel("t4"), tagModel("t5"),
                tagModel("shared")));
        relationships.setTags(tags);
        // "shared" is sent in both lists and counts once: 6 + 5 = 11 distinct keys
        relationships.setTagBindings(bindings("shared", "x", "b1", "1", "b2", "2", "b3", "3", "b4", "4", "b5", "5"));
        WorkspaceModel model = new WorkspaceModel();
        model.setAttributes(new HashMap<>(Map.of("name", "too-many", "terraform-version", "1.9.0")));
        model.setRelationships(relationships);
        WorkspaceData request = new WorkspaceData();
        request.setData(model);

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.createWorkspace("sample-org", request, fixture.currentUser));
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void updateTagBindingsWithAKeyRepeatedInTheRequestKeepsOneBinding() {
        TagFixture fixture = new TagFixture();
        Workspace workspace = fixture.workspace("app");

        TagBindingList result = fixture.service.updateTagBindings(workspace.getId().toString(),
                bindings("env", "prod", "env", "dev"), fixture.currentUser);

        assertEquals(List.of(Map.of("key", "env", "value", "dev")),
                result.getData().stream().map(TagBindingModel::getAttributes).toList());
        assertEquals(1, workspace.getWorkspaceTag().size());
    }

    @Test
    void getCurrentWorkspaceStatePropagatesAGenuineStorageFailureInsteadOfReportingNoState() {
        RemoteTfeService service = remoteTfeService();
        Organization organization = organization("sample-org");
        Workspace workspace = workspace("app", organization);
        when(workspaceRepository.getReferenceById(workspace.getId())).thenReturn(workspace);
        when(storageTypeService.getCurrentTerraformState(organization.getId().toString(), workspace.getId().toString()))
                .thenThrow(new io.terrakube.api.plugin.storage.StorageUnavailableException("S3 read failed", new RuntimeException("cause")));

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> service.getCurrentWorkspaceState(workspace.getId().toString()));
    }

    @Test
    void getCurrentOutputsPropagatesAGenuineStorageFailureInsteadOfReportingNoOutputs() {
        RemoteTfeService service = remoteTfeService();
        Organization organization = organization("sample-org");
        Workspace workspace = workspace("app", organization);
        when(workspaceRepository.findById(workspace.getId())).thenReturn(java.util.Optional.of(workspace));
        when(storageTypeService.getCurrentTerraformState(organization.getId().toString(), workspace.getId().toString()))
                .thenThrow(new io.terrakube.api.plugin.storage.StorageUnavailableException("S3 read failed", new RuntimeException("cause")));

        assertThrows(io.terrakube.api.plugin.storage.StorageUnavailableException.class,
                () -> service.getCurrentOutputs(workspace.getId().toString()));
    }

    /**
     * An organization whose single team manages workspaces; tags and workspace tags are kept in memory
     * behind the repository mocks.
     */
    private class TagFixture {
        final RemoteTfeService service = remoteTfeService();
        final JwtAuthenticationToken currentUser = currentUser();
        final Organization organization = organization("sample-org");
        final Team team = team("admins");
        final List<Tag> tags = new ArrayList<>();
        final List<Workspace> workspaces = new ArrayList<>();

        @SuppressWarnings("unchecked")
        TagFixture() {
            organization.setTeam(List.of(team));
            organization.setWorkspace(workspaces);
            when(teamTokenService.getCurrentGroups(currentUser)).thenReturn(List.of("admins"));
            when(rbacService.canManageWorkspace(team)).thenReturn(true);
            when(organizationRepository.getOrganizationByName("sample-org")).thenReturn(organization);
            when(tagRepository.findByOrganizationName("sample-org")).thenReturn(tags);
            when(tagRepository.getByOrganizationNameAndName(Mockito.eq("sample-org"), anyString()))
                    .thenAnswer(invocation -> findTag(invocation.getArgument(1)));
            when(tagRepository.save(any(Tag.class))).thenAnswer(invocation -> {
                Tag tag = invocation.getArgument(0);
                tag.setId(UUID.randomUUID());
                tags.add(tag);
                return tag;
            });
            when(tagRepository.findAllById(any())).thenAnswer(invocation -> {
                List<UUID> ids = new ArrayList<>();
                ((Iterable<UUID>) invocation.getArgument(0)).forEach(ids::add);
                return tags.stream().filter(tag -> ids.contains(tag.getId())).toList();
            });
            // workspace.getWorkspaceTag() plays the workspacetag table
            when(workspaceTagRepository.findByWorkspace(any(Workspace.class))).thenAnswer(invocation -> {
                Workspace workspace = invocation.getArgument(0);
                return workspace.getWorkspaceTag() == null ? List.of() : new ArrayList<>(workspace.getWorkspaceTag());
            });
            when(workspaceTagRepository.save(any(WorkspaceTag.class))).thenAnswer(invocation -> {
                WorkspaceTag workspaceTag = invocation.getArgument(0);
                if (workspaceTag.getId() == null) {
                    workspaceTag.setId(UUID.randomUUID());
                    Workspace workspace = workspaceTag.getWorkspace();
                    if (workspace.getWorkspaceTag() == null) {
                        workspace.setWorkspaceTag(new ArrayList<>());
                    }
                    workspace.getWorkspaceTag().add(workspaceTag);
                }
                return workspaceTag;
            });
            when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdAsc(any(), anyList()))
                    .thenReturn(Optional.empty());
        }

        Tag tag(String name) {
            Tag existing = findTag(name);
            if (existing != null) {
                return existing;
            }
            Tag tag = RemoteTfeServiceTest.this.tag(name, organization);
            tags.add(tag);
            return tag;
        }

        private Tag findTag(String name) {
            return tags.stream().filter(tag -> tag.getName().equals(name)).findFirst().orElse(null);
        }

        /** keyValues: key, value (null for a key-only tag), key, value, ... */
        Workspace workspace(String name, String... keyValues) {
            Workspace workspace = RemoteTfeServiceTest.this.workspace(name, organization);
            List<WorkspaceTag> workspaceTags = new ArrayList<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                WorkspaceTag workspaceTag = workspaceTag(tag(keyValues[i]));
                workspaceTag.setValue(keyValues[i + 1]);
                workspaceTag.setWorkspace(workspace);
                workspaceTags.add(workspaceTag);
            }
            workspace.setWorkspaceTag(workspaceTags);
            workspaces.add(workspace);
            when(workspaceRepository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
            return workspace;
        }
    }

    private static MultiValueMap<String, String> parameters(String... keyValues) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            parameters.add(keyValues[i], keyValues[i + 1]);
        }
        return parameters;
    }

    private static WorkspaceListQuery query(String... keyValues) {
        return WorkspaceListQuery.from(parameters(keyValues));
    }

    /** keyValues: key, value (null to omit the value like go-tfe does for key-only tags), ... */
    private static TagBindingList bindings(String... keyValues) {
        List<TagBindingModel> data = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            TagBindingModel binding = new TagBindingModel();
            binding.setType("tag-bindings");
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("key", keyValues[i]);
            if (keyValues[i + 1] != null) {
                attributes.put("value", keyValues[i + 1]);
            }
            binding.setAttributes(attributes);
            data.add(binding);
        }
        TagBindingList tagBindingList = new TagBindingList();
        tagBindingList.setData(data);
        return tagBindingList;
    }

    private static TagModel tagModel(String name) {
        TagModel tagModel = new TagModel();
        tagModel.setType("tags");
        tagModel.setAttributes(Map.of("name", name));
        return tagModel;
    }

    /** key-1=v1 ... key-N=vN, in the key, value, key, value form of TagFixture.workspace */
    private static String[] keyValues(int count) {
        String[] keyValues = new String[count * 2];
        for (int i = 0; i < count; i++) {
            keyValues[i * 2] = "key-" + (i + 1);
            keyValues[i * 2 + 1] = "v" + (i + 1);
        }
        return keyValues;
    }

    private static Map<String, Object> paginationOf(int currentPage, int pageSize, Integer prevPage, Integer nextPage,
                                                    int totalPages, int totalCount) {
        Map<String, Object> pagination = new HashMap<>();
        pagination.put("current-page", currentPage);
        pagination.put("page-size", pageSize);
        pagination.put("prev-page", prevPage);
        pagination.put("next-page", nextPage);
        pagination.put("total-pages", totalPages);
        pagination.put("total-count", totalCount);
        return pagination;
    }

    private static List<String> names(WorkspaceList workspaceList) {
        return workspaceList.getData().stream().map(model -> model.getAttributes().get("name").toString()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pagination(WorkspaceList workspaceList) {
        return (Map<String, Object>) workspaceList.getMeta().get("pagination");
    }

    @Test
    void getCurrentWorkspaceStateReadsTerraformVersionFromPlainState() throws Exception {
        RemoteTfeService service = remoteTfeService();
        Workspace workspace = workspace("plain", organization("sample-org"));
        when(workspaceRepository.getReferenceById(workspace.getId())).thenReturn(workspace);
        when(storageTypeService.getCurrentTerraformState(workspace.getOrganization().getId().toString(),
                workspace.getId().toString()))
                .thenReturn("{\"version\":4,\"terraform_version\":\"1.12.5\",\"serial\":7,\"lineage\":\"abc\"}"
                        .getBytes(StandardCharsets.UTF_8));

        StateData result = service.getCurrentWorkspaceState(workspace.getId().toString());

        assertEquals(7, result.getData().getAttributes().get("serial"));
        assertEquals("1.12.5", result.getData().getAttributes().get("terraform-version"));
    }

    @Test
    void getCurrentWorkspaceStateHandlesOpenTofuEncryptedState() throws Exception {
        RemoteTfeService service = remoteTfeService();
        Workspace workspace = workspace("encrypted", organization("sample-org"));
        when(workspaceRepository.getReferenceById(workspace.getId())).thenReturn(workspace);
        when(storageTypeService.getCurrentTerraformState(workspace.getOrganization().getId().toString(),
                workspace.getId().toString()))
                .thenReturn(("{\"serial\":22,\"lineage\":\"abc\",\"meta\":{\"key_provider.pbkdf2.state\":\"e30=\"},"
                        + "\"encrypted_data\":\"Y2lwaGVydGV4dA==\",\"encryption_version\":\"v0\"}")
                        .getBytes(StandardCharsets.UTF_8));

        StateData result = service.getCurrentWorkspaceState(workspace.getId().toString());

        assertEquals(22, result.getData().getAttributes().get("serial"));
        assertEquals("1.6.0", result.getData().getAttributes().get("terraform-version"));
        assertEquals("finalized", result.getData().getAttributes().get("status"));
    }

    private RemoteTfeService remoteTfeService() {
        RemoteTfeService service = remoteTfeServiceWithoutPolicyGuard();
        // A guard that blocks nothing (the default of a mock), so tests that are not about policy go through.
        service.setPolicyApprovalGuard(Mockito.mock(PolicyApprovalGuard.class));
        return service;
    }

    private RemoteTfeService remoteTfeServiceWithoutPolicyGuard() {
        return new RemoteTfeService(jobRepository, contentRepository, organizationRepository, workspaceRepository,
                historyRepository, templateRepository, scheduleJobService, "localhost", storageTypeService,
                stepRepository, redisTemplate, 1, tagRepository, workspaceTagRepository, teamTokenService,
                archiveRepository, accessRepository, encryptionService, addressRepository, projectRepository,
                variableRepository, globalVarRepository, rbacService, jobNotificationTrigger);
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

    @Test
    void validateWorkspaceIdTokenCanAccessState_sameWorkspace_returnsTrue() {
        RemoteTfeService service = remoteTfeService();
        String wsId = UUID.randomUUID().toString();
        assertTrue(service.validateWorkspaceIdTokenCanAccessState(wsId, wsId));
    }

    @Test
    void validateWorkspaceIdTokenCanAccessState_nullIds_returnsFalse() {
        RemoteTfeService service = remoteTfeService();
        assertFalse(service.validateWorkspaceIdTokenCanAccessState(null, UUID.randomUUID().toString()));
        assertFalse(service.validateWorkspaceIdTokenCanAccessState(UUID.randomUUID().toString(), null));
    }

    @Test
    void validateWorkspaceIdTokenCanAccessState_globalRemoteState_sameOrg_returnsTrue() {
        RemoteTfeService service = remoteTfeService();
        UUID orgId = UUID.randomUUID();
        Organization org = organization("test-org");
        org.setId(orgId);

        UUID targetWsId = UUID.randomUUID();
        Workspace targetWs = workspace("target", org);
        targetWs.setId(targetWsId);
        targetWs.setGlobalRemoteState(true);

        UUID consumerWsId = UUID.randomUUID();
        Workspace consumerWs = workspace("consumer", org);
        consumerWs.setId(consumerWsId);

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));
        when(workspaceRepository.findById(consumerWsId)).thenReturn(Optional.of(consumerWs));

        assertTrue(service.validateWorkspaceIdTokenCanAccessState(consumerWsId.toString(), targetWsId.toString()));
    }

    @Test
    void validateWorkspaceIdTokenCanAccessState_globalRemoteState_diffOrg_returnsFalse() {
        RemoteTfeService service = remoteTfeService();
        UUID org1Id = UUID.randomUUID();
        Organization org1 = organization("org1");
        org1.setId(org1Id);

        UUID org2Id = UUID.randomUUID();
        Organization org2 = organization("org2");
        org2.setId(org2Id);

        UUID targetWsId = UUID.randomUUID();
        Workspace targetWs = workspace("target", org1);
        targetWs.setId(targetWsId);
        targetWs.setGlobalRemoteState(true);

        UUID consumerWsId = UUID.randomUUID();
        Workspace consumerWs = workspace("consumer", org2);
        consumerWs.setId(consumerWsId);

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));
        when(workspaceRepository.findById(consumerWsId)).thenReturn(Optional.of(consumerWs));

        assertFalse(service.validateWorkspaceIdTokenCanAccessState(consumerWsId.toString(), targetWsId.toString()));
    }

    @Test
    void validateWorkspaceIdTokenCanAccessState_sharedIds_returnsTrue() {
        RemoteTfeService service = remoteTfeService();
        UUID targetWsId = UUID.randomUUID();
        Workspace targetWs = workspace("target", organization("org"));
        targetWs.setId(targetWsId);
        targetWs.setGlobalRemoteState(false);

        UUID consumerWsId = UUID.randomUUID();
        targetWs.setSharedIds(UUID.randomUUID() + "," + consumerWsId + "," + UUID.randomUUID());

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertTrue(service.validateWorkspaceIdTokenCanAccessState(consumerWsId.toString(), targetWsId.toString()));
    }

    @Test
    void validateWorkspaceIdTokenCanAccessState_sharedIds_notShared_returnsFalse() {
        RemoteTfeService service = remoteTfeService();
        UUID targetWsId = UUID.randomUUID();
        Workspace targetWs = workspace("target", organization("org"));
        targetWs.setId(targetWsId);
        targetWs.setGlobalRemoteState(false);
        targetWs.setSharedIds(UUID.randomUUID().toString());

        UUID consumerWsId = UUID.randomUUID();

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertFalse(service.validateWorkspaceIdTokenCanAccessState(consumerWsId.toString(), targetWsId.toString()));
    }

    @Test
    void validateUserCanOverridePolicy_internalMachineToken_returnsFalse() {
        RemoteTfeService service = remoteTfeService();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("iss", "TerrakubeInternal")
                .claim("workspaceId", UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken runnerToken = new JwtAuthenticationToken(jwt);

        assertFalse(service.validateUserCanOverridePolicy(Mockito.mock(io.terrakube.api.rs.policy.PolicyEvaluation.class), runnerToken));
    }

    @Test
    void getWorkspaceState_historyNotFound_returnsNull() {
        RemoteTfeService service = remoteTfeService();
        UUID historyId = UUID.randomUUID();
        when(historyRepository.findById(historyId)).thenReturn(Optional.empty());

        assertNull(service.getWorkspaceState(historyId.toString(), currentUser()));
    }

    @Test
    void getWorkspaceState_workspaceExecutor_unauthorized_throwsAccessDeniedException() {
        RemoteTfeService service = remoteTfeService();
        UUID historyId = UUID.randomUUID();
        UUID targetWsId = UUID.randomUUID();
        UUID consumerWsId = UUID.randomUUID();

        Organization org = organization("sample-org");
        Workspace targetWs = workspace("target-ws", org);
        targetWs.setId(targetWsId);
        targetWs.setGlobalRemoteState(false);
        targetWs.setSharedIds(UUID.randomUUID().toString());

        History history = new History();
        history.setId(historyId);
        history.setWorkspace(targetWs);

        when(historyRepository.findById(historyId)).thenReturn(Optional.of(history));
        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("iss", "TerrakubeInternal")
                .claim("workspaceId", consumerWsId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken runnerToken = new JwtAuthenticationToken(jwt);

        assertThrows(AccessDeniedException.class, () -> service.getWorkspaceState(historyId.toString(), runnerToken));
    }

    @Test
    void getWorkspaceState_workspaceExecutor_authorized_returnsStateData() {
        RemoteTfeService service = remoteTfeService();
        UUID historyId = UUID.randomUUID();
        UUID targetWsId = UUID.randomUUID();

        Organization org = organization("sample-org");
        Workspace targetWs = workspace("target-ws", org);
        targetWs.setId(targetWsId);

        History history = new History();
        history.setId(historyId);
        history.setWorkspace(targetWs);

        when(historyRepository.findById(historyId)).thenReturn(Optional.of(history));
        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("iss", "TerrakubeInternal")
                .claim("workspaceId", targetWsId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken runnerToken = new JwtAuthenticationToken(jwt);

        assertNotNull(service.getWorkspaceState(historyId.toString(), runnerToken));
    }

    @Test
    void getWorkspaceState_executorService_returnsStateData() {
        RemoteTfeService service = remoteTfeService();
        UUID historyId = UUID.randomUUID();
        UUID targetWsId = UUID.randomUUID();

        Organization org = organization("sample-org");
        Workspace targetWs = workspace("target-ws", org);
        targetWs.setId(targetWsId);

        History history = new History();
        history.setId(historyId);
        history.setWorkspace(targetWs);

        when(historyRepository.findById(historyId)).thenReturn(Optional.of(history));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("iss", "TerrakubeInternal")
                .claim("sub", "TerrakubeInternal (EXECUTOR)")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken serviceToken = new JwtAuthenticationToken(jwt);

        assertNotNull(service.getWorkspaceState(historyId.toString(), serviceToken));
    }

    @Test
    void getWorkspaceState_userToken_withManageStatePermission_returnsStateData() {
        RemoteTfeService service = remoteTfeService();
        UUID historyId = UUID.randomUUID();
        UUID targetWsId = UUID.randomUUID();

        Organization org = organization("sample-org");
        Team adminTeam = team("admin-team");
        org.setTeam(List.of(adminTeam));

        Workspace targetWs = workspace("target-ws", org);
        targetWs.setId(targetWsId);

        History history = new History();
        history.setId(historyId);
        history.setWorkspace(targetWs);

        when(historyRepository.findById(historyId)).thenReturn(Optional.of(history));

        JwtAuthenticationToken userToken = currentUser();
        when(teamTokenService.getCurrentGroups(userToken)).thenReturn(List.of("admin-team"));
        when(rbacService.canManageState(adminTeam)).thenReturn(true);

        assertNotNull(service.getWorkspaceState(historyId.toString(), userToken));
    }

    @Test
    void getWorkspaceState_userToken_withoutManageStatePermission_throwsAccessDeniedException() {
        RemoteTfeService service = remoteTfeService();
        UUID historyId = UUID.randomUUID();
        UUID targetWsId = UUID.randomUUID();

        Organization org = organization("sample-org");
        Team viewerTeam = team("viewer-team");
        org.setTeam(List.of(viewerTeam));

        Workspace targetWs = workspace("target-ws", org);
        targetWs.setId(targetWsId);

        History history = new History();
        history.setId(historyId);
        history.setWorkspace(targetWs);

        when(historyRepository.findById(historyId)).thenReturn(Optional.of(history));

        JwtAuthenticationToken userToken = currentUser();
        when(teamTokenService.getCurrentGroups(userToken)).thenReturn(List.of("viewer-team"));
        when(rbacService.canManageState(viewerTeam)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.getWorkspaceState(historyId.toString(), userToken));
    }
}
