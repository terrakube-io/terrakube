package io.terrakube.api.plugin.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.security.encryption.EncryptionService;
import io.terrakube.api.plugin.state.model.apply.ApplyRunData;
import io.terrakube.api.plugin.state.model.apply.ApplyRunModel;
import io.terrakube.api.plugin.state.model.configuration.ConfigurationData;
import io.terrakube.api.plugin.state.model.configuration.ConfigurationModel;
import io.terrakube.api.plugin.state.model.entitlement.EntitlementData;
import io.terrakube.api.plugin.state.model.entitlement.EntitlementModel;
import io.terrakube.api.plugin.state.model.generic.Resource;
import io.terrakube.api.plugin.state.model.organization.OrganizationData;
import io.terrakube.api.plugin.state.model.organization.OrganizationModel;
import io.terrakube.api.plugin.state.model.organization.capacity.OrgCapacityAttributes;
import io.terrakube.api.plugin.state.model.organization.capacity.OrgCapacityData;
import io.terrakube.api.plugin.state.model.organization.capacity.OrgCapacityModel;
import io.terrakube.api.plugin.state.model.outputs.OutputData;
import io.terrakube.api.plugin.state.model.outputs.StateOutputs;
import io.terrakube.api.plugin.state.model.plan.PlanRunData;
import io.terrakube.api.plugin.state.model.plan.PlanRunModel;
import io.terrakube.api.plugin.state.model.project.ProjectData;
import io.terrakube.api.plugin.state.model.project.ProjectList;
import io.terrakube.api.plugin.state.model.project.ProjectModel;
import io.terrakube.api.plugin.state.model.runs.*;
import io.terrakube.api.plugin.state.model.runs.Relationships;
import io.terrakube.api.plugin.state.model.state.StateData;
import io.terrakube.api.plugin.state.model.state.StateModel;
import io.terrakube.api.plugin.state.model.terraform.TerraformState;
import io.terrakube.api.plugin.state.model.workspace.*;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceModel;
import io.terrakube.api.plugin.state.model.workspace.state.consumers.StateConsumerList;
import io.terrakube.api.plugin.state.model.workspace.tags.TagDataList;
import io.terrakube.api.plugin.state.model.workspace.vcs.VcsRepo;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.content.Content;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.history.archive.Archive;
import io.terrakube.api.rs.workspace.history.archive.ArchiveType;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.TextStringBuilder;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class RemoteTfeService {

    private final ProjectRepository projectRepository;
    private AddressRepository addressRepository;
    private JobRepository jobRepository;
    private ContentRepository contentRepository;
    private OrganizationRepository organizationRepository;
    private WorkspaceRepository workspaceRepository;
    private HistoryRepository historyRepository;
    private TemplateRepository templateRepository;
    private ScheduleJobService scheduleJobService;
    private String hostname;
    private StorageTypeService storageTypeService;
    private StepRepository stepRepository;
    @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;
    private int executorCount;

    private TagRepository tagRepository;

    private WorkspaceTagRepository workspaceTagRepository;

    private TeamTokenService teamTokenService;

    private ArchiveRepository archiveRepository;

    private AccessRepository accessRepository;

    private EncryptionService encryptionService;

    public RemoteTfeService(JobRepository jobRepository,
                            ContentRepository contentRepository,
                            OrganizationRepository organizationRepository,
                            WorkspaceRepository workspaceRepository,
                            HistoryRepository historyRepository,
                            TemplateRepository templateRepository,
                            ScheduleJobService scheduleJobService,
                            @Value("${io.terrakube.hostname}") String hostname,
                            StorageTypeService storageTypeService,
                            StepRepository stepRepository,
                            @SuppressWarnings("rawtypes") RedisTemplate redisTemplate,
                            @Value("${io.terrakube.executor.replicas}") int executorCount,
                            TagRepository tagRepository,
                            WorkspaceTagRepository workspaceTagRepository,
                            TeamTokenService teamTokenService,
                            ArchiveRepository archiveRepository,
                            AccessRepository accessRepository,
                            EncryptionService encryptionService, AddressRepository addressRepository, ProjectRepository projectRepository) {
        this.jobRepository = jobRepository;
        this.contentRepository = contentRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceRepository = workspaceRepository;
        this.historyRepository = historyRepository;
        this.templateRepository = templateRepository;
        this.scheduleJobService = scheduleJobService;
        this.hostname = hostname;
        this.storageTypeService = storageTypeService;
        this.stepRepository = stepRepository;
        this.redisTemplate = redisTemplate;
        this.executorCount = executorCount;
        this.tagRepository = tagRepository;
        this.workspaceTagRepository = workspaceTagRepository;
        this.teamTokenService = teamTokenService;
        this.archiveRepository = archiveRepository;
        this.accessRepository = accessRepository;
        this.encryptionService = encryptionService;
        this.addressRepository = addressRepository;
        this.projectRepository = projectRepository;
    }

    private boolean validateTerrakubeUser(JwtAuthenticationToken currentUser) {
        return currentUser.getTokenAttributes().get("iss").equals("TerrakubeInternal");
    }

    private boolean validateUserIsMemberOrg(Organization organization, JwtAuthenticationToken currentUser) {
        if (validateTerrakubeUser(currentUser))
            return true;
        List<String> userGroups = teamTokenService.getCurrentGroups(currentUser);
        AtomicBoolean userIsMemberOrg = new AtomicBoolean(false);
        organization.getTeam().forEach(orgTeam -> {
            userGroups.forEach(userTeam -> {
                if (orgTeam.getName().equals(userTeam)) {
                    userIsMemberOrg.set(true);
                }
            });
        });
        return userIsMemberOrg.get();
    }

    private boolean validateUserLimitedWorkspaceAccess(Organization organization, JwtAuthenticationToken currentUser) {
        List<String> groups = teamTokenService.getCurrentGroups(currentUser);
        Optional<List<Access>> accessList = accessRepository.findAllByWorkspaceOrganizationIdAndNameIn(organization.getId(), groups);
        if (accessList.isPresent()) {
            log.debug("Groups Size: {},  Group Access {}", groups.size(), accessList.get().isEmpty());
            return !accessList.get().isEmpty();
        } else return false;

    }

    private boolean validateUserManageWorkspace(Organization organization, JwtAuthenticationToken currentUser) {
        if (validateTerrakubeUser(currentUser))
            return true;
        List<String> userGroups = teamTokenService.getCurrentGroups(currentUser);
        AtomicBoolean userWithManageWorkspace = new AtomicBoolean(false);
        organization.getTeam().forEach(orgTeam -> {
            userGroups.forEach(userTeam -> {
                if (orgTeam.getName().equals(userTeam) && orgTeam.isManageWorkspace()) {
                    userWithManageWorkspace.set(true);
                }
            });
        });
        return userWithManageWorkspace.get();
    }

    private boolean validateLimitedManageWorkspace(Workspace workspace, JwtAuthenticationToken currentUser) {
        List<Access> teamsWithLimitedAccess = workspace.getAccess();
        List<String> userGroups = teamTokenService.getCurrentGroups(currentUser);
        AtomicBoolean userWithManageWorkspace = new AtomicBoolean(false);
        if (teamsWithLimitedAccess != null && !teamsWithLimitedAccess.isEmpty())
            teamsWithLimitedAccess.forEach(access -> {
                if (access.isManageWorkspace() && userGroups.contains(access.getName())) {
                    userWithManageWorkspace.set(true);
                }
            });
        return userWithManageWorkspace.get();
    }

    private boolean validateUserManageJob(Workspace workspace, JwtAuthenticationToken currentUser) {
        if (validateTerrakubeUser(currentUser))
            return true;
        List<String> userGroups = teamTokenService.getCurrentGroups(currentUser);
        AtomicBoolean userWithManageWorkspace = new AtomicBoolean(false);
        workspace.getOrganization().getTeam().forEach(orgTeam -> {
            userGroups.forEach(userTeam -> {
                if (orgTeam.getName().equals(userTeam) && orgTeam.isManageJob()) {
                    userWithManageWorkspace.set(true);
                }
            });
        });

        if (workspace.getAccess() != null && !workspace.getAccess().isEmpty())
            workspace.getAccess().forEach(access -> {
                if (access.isManageJob() && userGroups.contains(access.getName())) {
                    userWithManageWorkspace.set(true);
                }
            });

        return userWithManageWorkspace.get();
    }

    EntitlementData getOrgEntitlementSet(String organizationName, JwtAuthenticationToken currentUser) {
        Organization organization = organizationRepository.getOrganizationByName(organizationName);

        if (organization != null && (validateUserIsMemberOrg(organization, currentUser) || validateUserLimitedWorkspaceAccess(organization, currentUser))) {
            EntitlementModel entitlementModel = new EntitlementModel();
            entitlementModel.setId("org-" + organizationName);
            Map<String, Object> entitlementAttributes = new HashMap<>();
            entitlementAttributes.put("operations", true);
            entitlementAttributes.put("private-module-registry", true);
            entitlementAttributes.put("sentinel", false);
            entitlementAttributes.put("run-tasks", false);
            entitlementAttributes.put("state-storage", true);
            entitlementAttributes.put("teams", false);
            entitlementAttributes.put("vcs-integrations", true);
            entitlementAttributes.put("usage-reporting", false);
            entitlementAttributes.put("user-limit", 5);
            entitlementAttributes.put("self-serve-billing", true);
            entitlementAttributes.put("audit-logging", false);
            entitlementAttributes.put("agents", false);
            entitlementAttributes.put("sso", false);
            entitlementModel.setAttributes(entitlementAttributes);
            entitlementModel.setType("entitlement-sets");
            EntitlementData entitlementData = new EntitlementData();
            entitlementData.setData(entitlementModel);

            log.info(entitlementData.toString());
            return entitlementData;
        } else {
            return null;
        }

    }

    OrgCapacityData getOrgCapacity(String organizationName, JwtAuthenticationToken currentUser) {
        Organization organization = organizationRepository.getOrganizationByName(organizationName);

        if (organization != null && (validateUserIsMemberOrg(organization, currentUser) || validateUserLimitedWorkspaceAccess(organization, currentUser))) {
            OrgCapacityData orgCapacityData = new OrgCapacityData();
            orgCapacityData.setData(new OrgCapacityModel());
            orgCapacityData.getData().setAttributes(new OrgCapacityAttributes());
            orgCapacityData.getData().getAttributes().setPending(0);

            orgCapacityData.getData().setType("organization-capacity");
            orgCapacityData.getData().getAttributes().setRunning(executorCount);
            log.info("orgCapacityData: {}", orgCapacityData);
            return orgCapacityData;
        } else {
            return null;
        }

    }

    OrganizationData getOrgInformation(String organizationName, JwtAuthenticationToken currentUser) {
        Organization organization = organizationRepository.getOrganizationByName(organizationName);
        if (organization != null && validateUserIsMemberOrg(organization, currentUser)) {
            OrganizationModel organizationModel = new OrganizationModel();
            organizationModel.setId(organizationName);
            organizationModel.setType("organizations");

            boolean isManageWorkspace = validateUserManageWorkspace(organization, currentUser);

            Map<String, Object> permissionMap = new HashMap<>();
            permissionMap.put("can-update", isManageWorkspace);
            permissionMap.put("can-destroy", isManageWorkspace);
            permissionMap.put("can-access-via-teams", false);
            permissionMap.put("can-create-module", false);
            permissionMap.put("can-create-team", false);
            permissionMap.put("can-create-workspace", isManageWorkspace);
            permissionMap.put("can-manage-users", false);
            permissionMap.put("can-manage-subscription", false);
            permissionMap.put("can-manage-sso", false);
            permissionMap.put("can-update-oauth", false);
            permissionMap.put("can-update-sentinel", false);
            permissionMap.put("can-update-ssh-keys", false);
            permissionMap.put("can-update-api-token", false);
            permissionMap.put("can-traverse", false);
            permissionMap.put("can-start-trial", false);
            permissionMap.put("can-update-agent-pools", false);
            permissionMap.put("can-manage-tags", isManageWorkspace);
            permissionMap.put("can-manage-public-modules", false);
            permissionMap.put("can-manage-public-providers", false);
            permissionMap.put("can-manage-run-tasks", isManageWorkspace);
            permissionMap.put("can-read-run-tasks", isManageWorkspace);
            permissionMap.put("can-create-provider", false);

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("permissions", permissionMap);
            attributes.put("name", organizationName);
            organizationModel.setAttributes(attributes);

            OrganizationData organizationData = new OrganizationData();
            organizationData.setData(organizationModel);
            return organizationData;
        } else {
            return null;
        }
    }

    WorkspaceData getWorkspace(String organizationName, String workspaceName, Map<String, Object> otherAttributes,
                               JwtAuthenticationToken currentUser) {
        Optional<Workspace> workspace = Optional
                .ofNullable(workspaceRepository.getByOrganizationNameAndName(organizationName, workspaceName));

        if (workspace.isPresent()) {
            log.info("Found Workspace Id: {} Terraform: {}", workspace.get().getId().toString(),
                    workspace.get().getTerraformVersion());
            WorkspaceData workspaceData = new WorkspaceData();

            WorkspaceModel workspaceModel = new WorkspaceModel();
            workspaceModel.setId(workspace.get().getId().toString());
            workspaceModel.setType("workspaces");
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("name", workspaceName);
            attributes.put("terraform-version", workspace.get().getTerraformVersion());
            attributes.put("locked", workspace.get().isLocked());
            attributes.put("auto-apply", false);
            attributes.put("execution-mode", workspace.get().getExecutionMode());
            attributes.put("global-remote-state", true);

            if (workspace.get().getFolder() != null
                    && (workspace.get().getVcs() != null || workspace.get().getSsh() != null)
                    && !workspace.get().getFolder().split(",")[0].equals("/")) {
                attributes.put("working-directory", workspace.get().getFolder().split(",")[0]);
            }

            boolean isManageWorkspace = validateUserManageWorkspace(workspace.get().getOrganization(), currentUser) || validateLimitedManageWorkspace(workspace.get(), currentUser);
            boolean isManageJob = validateUserManageJob(workspace.get(), currentUser);

            Map<String, Boolean> defaultAttributes = new HashMap<>();
            defaultAttributes.put("can-create-state-versions", isManageWorkspace);
            defaultAttributes.put("can-destroy", isManageWorkspace);
            defaultAttributes.put("can-force-unlock", isManageWorkspace);
            defaultAttributes.put("can-lock", isManageWorkspace);
            defaultAttributes.put("can-manage-run-tasks", isManageWorkspace);
            defaultAttributes.put("can-manage-tags", isManageWorkspace);
            defaultAttributes.put("can-queue-apply", isManageJob);
            defaultAttributes.put("can-queue-destroy", isManageWorkspace);
            defaultAttributes.put("can-queue-run", isManageJob);
            defaultAttributes.put("can-read-settings", true);
            defaultAttributes.put("can-read-state-versions", isManageWorkspace);
            defaultAttributes.put("can-read-variable", true);
            defaultAttributes.put("can-unlock", isManageWorkspace);
            defaultAttributes.put("can-update", isManageWorkspace);
            defaultAttributes.put("can-update-variable", isManageWorkspace);
            defaultAttributes.put("can-read-assessment-result", isManageWorkspace);
            defaultAttributes.put("can-force-delete", isManageWorkspace);
            // defaultAttributes.put("structured-run-output-enabled", true);

            attributes.put("permissions", defaultAttributes);

            if (workspace.get().getVcs() != null && !workspace.get().isAllowRemoteApply()) {
                VcsRepo vcsRepo = new VcsRepo();
                vcsRepo.setBranch(workspace.get().getBranch());
                vcsRepo.setRepositoryHttpUrl(workspace.get().getSource());
                attributes.put("vcs-repo", vcsRepo);
            }

            otherAttributes.forEach((key, value) -> attributes.putIfAbsent(key, value));

            workspaceModel.setAttributes(attributes);
            workspaceData.setData(workspaceModel);

            Optional<Job> currentJob = jobRepository.findFirstByWorkspaceAndStatusInOrderByIdAsc(workspace.get(),
                    Arrays.asList(JobStatus.pending, JobStatus.running, JobStatus.queue, JobStatus.waitingApproval));
            if (currentJob.isPresent()) {
                log.info("Adding current job id: {}", currentJob.get().getId());
                workspaceModel.setRelationships(new io.terrakube.api.plugin.state.model.workspace.Relationships());
                CurrentRunRelationship currentRunRelationship = new CurrentRunRelationship();
                currentRunRelationship.setData(new Resource());
                currentRunRelationship.getData().setId(String.valueOf(currentJob.get().getId()));
                currentRunRelationship.getData().setType("runs");
                workspaceModel.getRelationships().setCurrentRun(currentRunRelationship);
            }

            if (workspace.get().getProject() != null) {
                Project project = workspace.get().getProject();
                log.info("Adding project information: {}", project.getId());
                if (workspaceModel.getRelationships() == null) {
                    workspaceModel.setRelationships(new io.terrakube.api.plugin.state.model.workspace.Relationships());
                }
                workspaceModel.getRelationships().setProject(new ProjectRelationship());
                workspaceModel.getRelationships().getProject().setData(new Resource());
                workspaceModel.getRelationships().getProject().getData().setId(project.getId().toString());
                workspaceModel.getRelationships().getProject().getData().setType("projects");
            }

            return workspaceData;
        } else {
            return null;
        }

    }

    StateConsumerList getWorkspaceStateConsumers(String workspaceId, JwtAuthenticationToken currentUser) {
        Optional<Workspace> workspaceFound = Optional
                .ofNullable(workspaceRepository.getReferenceById(UUID.fromString(workspaceId)));

        StateConsumerList stateConsumerList = new StateConsumerList();
        stateConsumerList.setData(new ArrayList());

        workspaceFound.ifPresent(workspaceData -> {
            log.info("Workspace found {}, generating workspace list from organization", workspaceData.getName());
            workspaceData.getOrganization().getWorkspace().forEach(workspace -> {
                if (!workspace.getId().toString().equals(workspaceId)) {
                    log.info("Adding workspace {} as state consumers", workspace.getName());
                    stateConsumerList.getData().add(getWorkspace(workspace.getOrganization().getName(),
                            workspace.getName(), new HashMap(), currentUser).getData());
                }
            });
        });

        return stateConsumerList;

    }

    WorkspaceList listWorkspace(String organizationName, Optional<String> searchTags, Optional<String> searchName,
                                JwtAuthenticationToken currentUser) {
        WorkspaceList workspaceList = new WorkspaceList();
        workspaceList.setData(new ArrayList());

        if (searchTags.isPresent()) {
            String searchTagData = searchTags.get();
            List<String> listTags = Arrays.stream(searchTagData.split(",")).toList();
            log.info("Searching workspaces with tags: {}", searchTags);
            for (Workspace workspace : organizationRepository.getOrganizationByName(organizationName).getWorkspace()) {
                List<WorkspaceTag> workspaceTagList = workspace.getWorkspaceTag();
                int matchingTags = 0;

                for (WorkspaceTag workspaceTag : workspaceTagList) {
                    Tag tag = tagRepository.getReferenceById(UUID.fromString(workspaceTag.getTagId()));
                    if (listTags.indexOf(tag.getName()) > -1) {
                        matchingTags++;
                    }
                }
                log.info("Workspace {} Tags Count {} Searching Tag Quantity {} Matched {}", workspace.getName(),
                        workspaceTagList.size(), listTags.size(), matchingTags);
                if (matchingTags == listTags.size()) {
                    workspaceList.getData().add(
                            getWorkspace(organizationName, workspace.getName(), new HashMap(), currentUser).getData());
                }
            }
        }

        if (searchName.isPresent()) {
            String searchNameData = searchName.get();
            log.info("Searching workspaces with name prefix: {}", searchNameData);
            Optional<List<Workspace>> workspaceListByName = workspaceRepository
                    .findWorkspacesByOrganizationNameAndNameStartingWith(organizationName, searchNameData);
            if (workspaceListByName.isPresent())
                for (Workspace workspace : workspaceListByName.get()) {
                    workspaceList.getData().add(
                            getWorkspace(organizationName, workspace.getName(), new HashMap(), currentUser).getData());
                }
        }
        return workspaceList;
    }

    boolean updateWorkspaceTags(String workspaceId, TagDataList tagDataList) {
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));
        tagDataList.getData().forEach(tagModel -> {
            Tag tag = searchOrCreateTagOrganization(workspace, tagModel.getAttributes().get("name"));
            log.info("Updating tag {} in Workspace {}", tagModel.getAttributes().get("name"), workspace.getName());
            if (workspaceTagRepository.getByWorkspaceAndTagId(workspace, tag.getId().toString()) == null) {
                log.info("Tag {} does not exist in workspace {}, adding new tag to workspace...",
                        tagModel.getAttributes().get("name"), workspace.getName());
                WorkspaceTag newWorkspaceTag = new WorkspaceTag();
                //newWorkspaceTag.setId(UUID.randomUUID());
                newWorkspaceTag.setTagId(tag.getId().toString());
                newWorkspaceTag.setWorkspace(workspace);
                workspaceTagRepository.save(newWorkspaceTag);
            } else {
                log.info("Tag {} exist in workspace {}, there is no need to update",
                        tagModel.getAttributes().get("name"), workspace.getName());
            }
        });

        return true;
    }

    synchronized Tag searchOrCreateTagOrganization(Workspace workspace, String tagName) {
        log.info("Checking if Tag {} exists inside Organization {}", tagName, workspace.getOrganization().getName());
        Tag tag = tagRepository.getByOrganizationNameAndName(workspace.getOrganization().getName(), tagName);

        if (tag == null) {
            log.info("Creating new tag {} in Org {}", tagName, workspace.getOrganization().getName());
            tag = new Tag();
            try {
                //tag.setId(UUID.randomUUID());
                tag.setName(tagName);
                tag.setOrganization(workspace.getOrganization());
                tag = tagRepository.save(tag);
            } catch (Exception exception) {
                log.error(exception.getMessage());
                tag = tagRepository.getByOrganizationNameAndName(workspace.getOrganization().getName(), tagName);
            }

        }
        return tag;
    }

    WorkspaceData updateWorkspace(String workspaceId, WorkspaceData workspaceData, JwtAuthenticationToken currentUser) {
        Optional<Workspace> workspace = Optional
                .ofNullable(workspaceRepository.getReferenceById(UUID.fromString(workspaceId)));

        log.info("Updating existing workspace {} in {}", workspace.get().getName(),
                workspace.get().getOrganization().getName());

        Workspace updatedWorkspace = workspace.get();
        updatedWorkspace
                .setTerraformVersion(workspaceData.getData().getAttributes().get("terraform-version").toString());

        workspaceRepository.save(updatedWorkspace);

        return getWorkspace(updatedWorkspace.getOrganization().getName(), updatedWorkspace.getName(), new HashMap<>(),
                currentUser);
    }

    WorkspaceData createWorkspace(String organizationName, WorkspaceData workspaceData,
                                  JwtAuthenticationToken currentUser) {
        Optional<Workspace> workspace = Optional.ofNullable(workspaceRepository.getByOrganizationNameAndName(
                organizationName, workspaceData.getData().getAttributes().get("name").toString()));

        if (!validateUserManageWorkspace(organizationRepository.getOrganizationByName(organizationName), currentUser))
            return null;

        if (workspace.isEmpty()) {
            log.info("Creating new workspace {} in {}", workspaceData.getData().getAttributes().get("name").toString(),
                    organizationName);

            Organization organization = organizationRepository.getOrganizationByName(organizationName);
            Workspace newWorkspace = new Workspace();
            //newWorkspace.setId(UUID.randomUUID());
            newWorkspace.setName(workspaceData.getData().getAttributes().get("name").toString());
            String terraformVersion = "";
            if (workspaceData.getData().getAttributes().get("terraform-version") != null) {
                terraformVersion = workspaceData.getData().getAttributes().get("terraform-version").toString();
            } else {
                terraformVersion = "1.4.6";
                log.warn("Using default terraform version: {}", terraformVersion);
            }
            newWorkspace.setTerraformVersion(terraformVersion);
            newWorkspace.setSource("empty");
            newWorkspace.setBranch("remote-content");
            newWorkspace.setOrganization(organization);

            if (workspaceData.getData().getRelationships() != null && workspaceData.getData().getRelationships().getProject() != null) {
                log.info("Setting project for workspace {}", newWorkspace.getName());
                String projectId = workspaceData.getData().getRelationships().getProject().getData().getId();
                log.info("Project Id: {}", projectId);
                Project project = projectRepository.getReferenceById(UUID.fromString(projectId));
                newWorkspace.setProject(project);
            }
            workspaceRepository.save(newWorkspace);
        }
        Map<String, Object> otherAttributes = new HashMap<>();
        otherAttributes.put("locked", false);
        return getWorkspace(organizationName, workspaceData.getData().getAttributes().get("name").toString(),
                otherAttributes, currentUser);
    }

    WorkspaceData updateWorkspaceLock(String workspaceId, boolean locked, JwtAuthenticationToken currentUser) {
        log.info("Update Lock Workspace: {} to {}", workspaceId, locked);
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));
        log.info("Workspace {} Organization {} ", workspace.getId().toString(),
                workspace.getOrganization().getId().toString());
        workspace.setLocked(locked);
        workspaceRepository.save(workspace);
        String organizationName = workspace.getOrganization().getName();
        Map<String, Object> otherAttributes = new HashMap<>();

        otherAttributes.put("locked", false);
        return getWorkspace(organizationName, workspace.getName(), otherAttributes, currentUser);
    }

    public boolean isWorkspaceLocked(String workspaceId) {
        Optional<Workspace> workspace = workspaceRepository.findById(UUID.fromString(workspaceId));
        log.info("Checking Lock for Workspace: {} is locked {}", workspaceId, workspace.get().isLocked());
        return workspace.get().isLocked();
    }

    StateData createWorkspaceState(String workspaceId, StateData stateData) {
        log.info("Creating new workspace state for {}", workspaceId);
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));

        String terraformState = null;

        if (stateData.getData().getAttributes().get("state") != null) {
            byte[] decodedBytes = stateData.getData().getAttributes().get("state").toString().getBytes();
            terraformState = new String(Base64.getMimeDecoder().decode(decodedBytes));
        }

        // According to API docs json-state is optional so if the value is not available
        // we will upload a default "{}"
        byte[] decodedBytesJson = (stateData.getData().getAttributes().get("json-state") != null)
                ? stateData.getData().getAttributes().get("json-state").toString().getBytes()
                : "{}".getBytes(StandardCharsets.UTF_8);
        String terraformStateJson = new String(Base64.getMimeDecoder().decode(decodedBytesJson));

        // create dummy job
        Job job = new Job();
        job.setWorkspace(workspace);
        job.setOrganization(workspace.getOrganization());
        job.setStatus(JobStatus.completed);
        job.setRefresh(true);
        job.setPlanChanges(true);
        job.setRefreshOnly(false);
        job = jobRepository.save(job);

        // dummy step
        Step step = new Step();
        step.setJob(job);
        step.setName("Dummy State Uploaded");
        step.setStatus(JobStatus.completed);
        step.setStepNumber(100);
        stepRepository.save(step);

        // create dummy history
        History history = new History();
        history.setOutput("");
        history.setSerial(stateData.getData().getAttributes().get("serial") != null
                ? Integer.parseInt(stateData.getData().getAttributes().get("serial").toString())
                : 1);
        history.setLineage(stateData.getData().getAttributes().get("lineage") != null
                ? stateData.getData().getAttributes().get("lineage").toString()
                : null);
        history.setJobReference(String.valueOf(job.getId()));
        history.setWorkspace(workspace);

        history = historyRepository.save(history);

        history.setOutput(String
                .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                        hostname,
                        workspace.getOrganization().getId().toString(),
                        workspace.getId().toString(),
                        history.getId().toString()));

        history = historyRepository.save(history);

        if (terraformState != null) {
            // upload state to backend storage
            storageTypeService.uploadState(workspace.getOrganization().getId().toString(), workspace.getId().toString(),
                    terraformState, history.getId().toString());
        } else {
            log.warn("State field is empty, workspace state should be uploaded, creating new archive ...");
            Archive archiveState = new Archive();
            archiveState.setHistory(history);
            archiveState.setType(ArchiveType.RAW);
            archiveRepository.save(archiveState).getId();

            Archive archiveJsonState = new Archive();
            archiveJsonState.setHistory(history);
            archiveJsonState.setType(ArchiveType.JSON);
            archiveRepository.save(archiveJsonState).getId();
        }

        // upload json state to backend storage
        storageTypeService.uploadTerraformStateJson(workspace.getOrganization().getId().toString(),
                workspace.getId().toString(), terraformStateJson, history.getId().toString());

        return getWorkspaceState(history.getId().toString());
    }

    StateData getWorkspaceState(String historyId) {
        Optional<History> historyTmp = historyRepository.findById(UUID.fromString(historyId));

        if (historyTmp.isPresent()) {
            History history = historyTmp.get();
            StateData response = new StateData();
            response.setData(new StateModel());
            response.getData().setId(historyId.toString());
            response.getData().setType("state-versions");

            Map<String, Object> responseAttributes = new HashMap<>();
            responseAttributes.put("vcs-commit-sha", null);
            responseAttributes.put("vcs-commit-url", null);

            responseAttributes.put("hosted-state-download-url", String
                    .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/terraform.tfstate",
                            hostname,
                            history.getWorkspace().getOrganization().getId().toString(),
                            history.getWorkspace().getId()));
            responseAttributes.put("hosted-json-state-download-url", String
                    .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                            hostname,
                            history.getWorkspace().getOrganization().getId().toString(),
                            history.getWorkspace().getId(),
                            history.getId().toString()));

            Optional<Archive> archiveRawState = archiveRepository.findByHistoryAndType(history, ArchiveType.RAW);
            Optional<Archive> archiveJsonState = archiveRepository.findByHistoryAndType(history, ArchiveType.JSON);

            // if archive exist return pending otherwise return finilized
            if (archiveRawState.isEmpty() && archiveJsonState.isEmpty()) {
                responseAttributes.put("status", "pending");
            } else {
                responseAttributes.put("status", "finalized");
            }

            if (archiveRawState.isPresent())
                responseAttributes.put("hosted-state-upload-url", String
                        .format("https://%s/tfstate/v1/archive/%s/terraform.tfstate",
                                hostname,
                                archiveRawState.get().getId()));

            if (archiveJsonState.isPresent())
                responseAttributes.put("hosted-json-state-upload-url", String
                        .format("https://%s/tfstate/v1/archive/%s/terraform.json.tfstate",
                                hostname,
                                archiveJsonState.get().getId()));

            response.getData().setAttributes(responseAttributes);

            log.info("Download State URL: {}", String
                    .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/terraform.tfstate",
                            hostname,
                            history.getWorkspace().getOrganization().getId().toString(),
                            history.getWorkspace().getId()));

            log.info("Download State JSON URL: {}", String
                    .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                            hostname,
                            history.getWorkspace().getOrganization().getId().toString(),
                            history.getWorkspace().getId(),
                            history.getId().toString()));

            log.info(response.toString());
            return response;
        } else
            return null;
    }

    StateData getCurrentWorkspaceState(String workspaceId) throws JsonProcessingException {
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));

        log.info("Searching for existing terraform state for {} in the storage service", workspace.getId());
        byte[] currentState;
        try {
            currentState = storageTypeService.getCurrentTerraformState(workspace.getOrganization().getId().toString(),
                    workspaceId);
        } catch (Exception ex) {
            log.error("Exception searching state in storage");
            log.error(ex.getMessage());
            currentState = new byte[0];
        }
        if (currentState.length > 0) {
            String historyId = UUID.randomUUID().toString();
            StateData currentStateData = new StateData();
            currentStateData.setData(new StateModel());
            currentStateData.getData().setId(historyId);
            currentStateData.getData().setType("state-versions");

            Map<String, Object> responseAttributes = new HashMap<>();
            responseAttributes.put("vcs-commit-url", null);
            responseAttributes.put("vcs-commit-sha", null);
            responseAttributes.put("hosted-state-download-url", String
                    .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/terraform.tfstate",
                            hostname,
                            workspace.getOrganization().getId().toString(),
                            workspace.getId().toString()));

            String stateString = new String(currentState, StandardCharsets.UTF_8);
            Map<String, Object> result = new ObjectMapper().readValue(stateString, HashMap.class);
            responseAttributes.put("serial", Integer.valueOf(result.get("serial").toString()));
            responseAttributes.put("terraform-version", result.get("terraform_version").toString());
            responseAttributes.put("status", "finalized");
            currentStateData.getData().setAttributes(responseAttributes);

            log.info(currentStateData.toString());
            return currentStateData;
        } else
            return null;
    }

    ConfigurationData createConfigurationVersion(String workspaceId, ConfigurationData configurationData) {
        log.info("Create Configuration Version {}", configurationData.toString());
        log.info("Speculative {}", configurationData.getData().getAttributes().get("speculative"));
        log.info("Auto Queue Runs {}", configurationData.getData().getAttributes().get("auto-queue-runs"));

        Content content = new Content();
        content.setStatus("pending");
        content.setSource("tfe-api");
        content.setSpeculative((boolean) configurationData.getData().getAttributes().get("speculative"));
        content.setWorkspace(workspaceRepository.getReferenceById(UUID.fromString(workspaceId)));

        content = contentRepository.save(content);
        log.info("New content with id {} saved", content.getId().toString());
        configurationData.getData().setId(content.getId().toString());
        configurationData.getData().setType("configuration-versions");
        configurationData.getData().getAttributes().put("error", null);
        configurationData.getData().getAttributes().put("error-message", null);
        configurationData.getData().getAttributes().put("status", "pending");
        configurationData.getData().getAttributes().put("upload-url", String
                .format("https://%s/remote/tfe/v2/configuration-versions/%s", hostname, content.getId().toString()));
        log.info("upload-url {}", String.format("https://%s/remote/tfe/v2/configuration-versions/%s", hostname,
                content.getId().toString()));
        return configurationData;
    }

    ConfigurationData uploadFile(String contentId, InputStream inputStream) {
        storageTypeService.createContentFile(contentId, inputStream);
        log.info("Searching Content Id {}", contentId);

        Content content = contentRepository.getReferenceById(UUID.fromString(contentId));
        content.setStatus("uploaded");
        contentRepository.save(content);
        return searchConfiguration(contentId);
    }

    byte[] getContentFile(String contentId) {
        return storageTypeService.getContentFile(contentId);
    }

    ConfigurationData searchConfiguration(String contentId) {
        Content content = contentRepository.getReferenceById(UUID.fromString(contentId));
        ConfigurationData configurationData = new ConfigurationData();
        ConfigurationModel configurationModel = new ConfigurationModel();
        configurationModel.setType("configuration-versions");
        configurationModel.setId(content.getId().toString());
        configurationModel.setAttributes(new HashMap<>());
        configurationData.setData(configurationModel);

        configurationData.getData().getAttributes().put("error", null);
        configurationData.getData().getAttributes().put("error-message", null);
        configurationData.getData().getAttributes().put("error-message", null);
        configurationData.getData().getAttributes().put("source", content.getSource());
        configurationData.getData().getAttributes().put("status", content.getStatus());
        configurationData.getData().getAttributes().put("speculative", content.isSpeculative());
        configurationData.getData().getAttributes().put("auto-queue-runs", content.isAutoQueueRuns());

        return configurationData;
    }

    RunsData createRun(RunsData runsData) throws SchedulerException, ParseException {
        String workspaceId = runsData.getData().getRelationships().getWorkspace().getData().getId();
        String configurationId = runsData.getData().getRelationships().getConfigurationVersion().getData().getId();
        boolean isDestroy = runsData.getData().getAttributes().get("is-destroy") != null
                ? (boolean) runsData.getData().getAttributes().get("is-destroy")
                : false;

        boolean autoApply = runsData.getData().getAttributes().get("auto-apply") != null
                ? (boolean) runsData.getData().getAttributes().get("auto-apply")
                : false;

        log.info("Creating new Terrakube Job");
        log.info("Workspace {} Configuration {}", workspaceId, configurationId);
        log.info("isDestroy {} autoApply {}", isDestroy, autoApply);

        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));
        String sourceTarGz = String.format("https://%s/remote/tfe/v2/configuration-versions/%s/terraformContent.tar.gz",
                hostname, configurationId);
        // we need to update the source only if the VCS connection is null and the
        // branch is other than "remote-content"
        if (workspace.getVcs() == null && workspace.getBranch().equals("remote-content")) {
            workspace.setSource(sourceTarGz);
        }
        workspace = workspaceRepository.save(workspace);
        Template template = templateRepository.getByOrganizationNameAndName(
                workspace.getOrganization().getName(),
                getTemplateName(configurationId, isDestroy));
        log.info("Creating Job");
        Job job = new Job();
        job.setPlanChanges(true);
        job.setRefresh(runsData.getData().getAttributes().get("refresh") != null
                ? (boolean) runsData.getData().getAttributes().get("refresh")
                : true);
        job.setRefreshOnly(runsData.getData().getAttributes().get("refresh-only") != null
                ? (boolean) runsData.getData().getAttributes().get("refresh-only")
                : false);
        job.setWorkspace(workspace);
        job.setOrganization(workspace.getOrganization());
        job.setStatus(JobStatus.pending);
        job.setAutoApply(autoApply);
        job.setComments("terraform-cli");
        job.setVia("CLI");
        job.setTemplateReference(template.getId().toString());
        // if the vcs connection is not null, we need to override the value inside the
        // job
        if (workspace.getVcs() != null || workspace.getSsh() != null) {
            log.warn(
                    "Workspace is using VCS connection, overriding vcs source and branch to run job using a remote plan");
            job.setOverrideBranch("remote-content");
            job.setOverrideSource(sourceTarGz);
        }

        job = jobRepository.save(job);
        log.info("Job Created");

        if(runsData.getData().getAttributes().get("target-addrs") != null) {
            log.info("Target Addresses {}", runsData.getData().getAttributes().get("target-addrs"));
            List<String> targetAddrs = (List<String>) runsData.getData().getAttributes().get("target-addrs");
            for(String target: targetAddrs){
                Address address = new Address();
                address.setName(target);
                address.setType(AddressType.TARGET);
                address.setJob(job);
                addressRepository.save(address);
            }
            log.info("Target Addresses Java List {}", targetAddrs);
        }

        if(runsData.getData().getAttributes().get("replace-addrs") != null) {
            log.info("Replace Addresses {}", runsData.getData().getAttributes().get("replace-addrs"));
            List<String> replaceAddres = (List<String>) runsData.getData().getAttributes().get("replace-addrs");
            for(String replace: replaceAddres){
                Address address = new Address();
                address.setName(replace);
                address.setType(AddressType.REPLACE);
                address.setJob(job);
                addressRepository.save(address);
            }
            log.info("Replace Addresses Java List {}", replaceAddres);
        }

        scheduleJobService.createJobContext(job);
        log.info("Job Context Created");
        return getRun(job.getId(), null);
    }

    private String getTemplateName(String configurationId, boolean isDestroy) {
        // get run speculative if false get Terraform Plan/Apply else just Plan or
        // destroy
        Content content = contentRepository.getReferenceById(UUID.fromString(configurationId));
        if (isDestroy) {
            return "Terraform-Plan/Destroy-Cli";
        } else if (content.isSpeculative()) {
            return "Plan";
        } else {
            return "Terraform-Plan/Apply-Cli";
        }
    }

    RunsData getRun(int runId, String include) {
        log.info("Searching Run {}", runId);
        Job job = jobRepository.getReferenceById(Integer.valueOf(runId));

        if (job.getWorkspace() != null) {
            RunsData runsData = new RunsData();
            RunsModel runsModel = new RunsModel();
            runsModel.setId("run-" + runId);
            runsModel.setType("runs");
            runsModel.setAttributes(new HashMap<>());

            String planStatus = "running";

            switch (job.getStatus()) {
                case completed:
                    planStatus = "finished";
                    break;
                case pending:
                    // check if any step is in status pending else we need to return running
                    // check if workspace is not lock return running too
                    Optional<Step> optionalStep = stepRepository.findFirstByJobIdOrderByStepNumber(job.getId());
                    if (optionalStep.isPresent()) {
                        Step step = optionalStep.get();
                        if (step.getStatus().equals(JobStatus.pending)) {
                            planStatus = "pending";
                        } else {
                            planStatus = "running";
                        }
                    } else {
                        planStatus = "pending";
                    }
                    break;
                case running:
                case queue:
                    planStatus = "running";
                    break;
                case failed:
                    planStatus = "errored";
                    break;
                default:
                    planStatus = "unknown";
                    break;
            }

            runsModel.getAttributes().put("status", planStatus);
            runsModel.getAttributes().put("has-changes", job.isPlanChanges());
            runsModel.getAttributes().put("resource-additions", 1);
            runsModel.getAttributes().put("resource-changes", 1);
            runsModel.getAttributes().put("resource-destructions", 0);

            HashMap<String, Object> actions = new HashMap<>();
            actions.put("is-confirmable", true);
            actions.put("is-discardable", true);
            runsModel.getAttributes().put("actions", actions);

            HashMap<String, Object> permissions = new HashMap<>();
            permissions.put("can-apply", true);
            runsModel.getAttributes().put("permissions", permissions);

            runsData.setData(runsModel);
            Relationships relationships = new Relationships();
            PlanModel planModel = new PlanModel();
            planModel.setData(new Resource());
            planModel.getData().setType("plans");
            planModel.getData().setId(String.valueOf(runId));
            relationships.setPlan(planModel);

            ApplyModel applyModel = new ApplyModel();
            applyModel.setData(new Resource());
            applyModel.getData().setType("applies");
            applyModel.getData().setId(String.valueOf(runId));
            relationships.setApply(applyModel);

            io.terrakube.api.plugin.state.model.runs.WorkspaceModel workspaceModel = new io.terrakube.api.plugin.state.model.runs.WorkspaceModel();
            workspaceModel.setData(new Resource());
            workspaceModel.getData().setId(job.getWorkspace().getId().toString());
            workspaceModel.getData().setType("workspaces");
            relationships.setWorkspace(workspaceModel);

            RunEventsModel runEventsModel = new RunEventsModel();
            runEventsModel.setData(new ArrayList<Resource>());
            relationships.setRunEventsModel(runEventsModel);

            log.info("Included: {}", include);
            // if(include != null && include.equals("workspace")){
            // runsData.setIncluded(new ArrayList());
            // runsData.getIncluded().add(getWorkspace(job.getOrganization().getName(),
            // job.getWorkspace().getName(), new HashMap<>()));
            // }

            runsData.getData().setRelationships(relationships);

            log.info("{}", runsData.toString());
            return runsData;
        } else
            return null;
    }

    RunsDataList getRunsQueue(String organizationName) {
        RunsDataList runsDataList = new RunsDataList();
        runsDataList.setData(new ArrayList<RunsModel>());
        List<Job> jobList = jobRepository.findAllByOrganizationNameAndStatusInOrderByIdAsc(
                organizationName,
                Arrays.asList(
                        JobStatus.pending,
                        JobStatus.running,
                        JobStatus.queue,
                        JobStatus.waitingApproval));

        int runQueue = 0;
        for (Job job : jobList) {
            log.info("Run Queue {} job {}", runQueue, job.getId());
            Optional<RunsData> runsData = Optional.ofNullable(getRun(job.getId(), null));
            if (runsData.isPresent()) {
                RunsModel runsModel = runsData.get().getData();
                runsModel.getAttributes().put("position-in-queue", runQueue);
                runsDataList.getData().add(runsModel);
                runQueue = runQueue + 1;
            }
        }
        runsDataList.setCurrentPage(1);
        runsDataList.setTotalPages(1);
        return runsDataList;
    }

    RunsDataList getWorkspaceRuns(String workspaceId) {
        RunsDataList runsDataList = new RunsDataList();
        runsDataList.setData(new ArrayList<RunsModel>());
        Optional<Workspace> workspaceList = workspaceRepository.findById(UUID.fromString(workspaceId));
        if (!workspaceList.isPresent()) {
            return runsDataList;
        }
        Workspace workspace = workspaceList.get();
        jobRepository.findAllByWorkspaceAndStatusInOrderByIdDesc(workspace, Arrays.asList(
                JobStatus.pending,
                JobStatus.running,
                JobStatus.queue,
                JobStatus.waitingApproval)).forEach(job -> {
            log.info("Run Workspace {} job {}", workspace.getName(), job.getId());
            Optional<RunsData> runsData = Optional.ofNullable(getRun(job.getId(), null));
            runsDataList.getData().add(runsData.get().getData());
        });

        runsDataList.setCurrentPage(1);
        runsDataList.setTotalPages(1);
        return runsDataList;
    }

    RunsData runApply(int runId) {

        Job job = jobRepository.getReferenceById(Integer.valueOf(runId));
        if (job.getStep() != null && !job.getStep().isEmpty()) {

            // We need to check if the run was only a plan
            // If the job status is completed and only a plan was executed
            // we need to add the apply so the job execution can continue
            List<Step> steps = job.getStep();
            if (job.getStatus().equals(JobStatus.completed) && steps.size() == 1
                    && steps.get(0).getStepNumber() == 100) {
                log.warn("Only a plan was executed, adding apply steps to job execution");
                Step approvalStep = new Step();
                approvalStep.setStatus(JobStatus.pending);
                approvalStep.setStepNumber(150);
                approvalStep.setName("Approve Plan from Terraform CLI");
                approvalStep.setJob(job);
                approvalStep = stepRepository.save(approvalStep);
                log.warn("Approval Step {} added for job {}", approvalStep.getId(), job.getId());

                Step applyStep = new Step();
                applyStep.setStatus(JobStatus.pending);
                applyStep.setStepNumber(200);
                applyStep.setName("Terraform Apply from Terraform CLI");
                applyStep.setJob(job);
                applyStep = stepRepository.save(applyStep);
                log.warn("Apply Step {} added for job {}", applyStep.getId(), job.getId());

                Template cliTemplate = templateRepository.getByOrganizationNameAndName(job.getOrganization().getName(),
                        "Terraform-Plan/Apply-Cli");
                job.setTcl(cliTemplate.getTcl());
                job.setStatus(JobStatus.pending);
                job = jobRepository.save(job);
                log.warn("Update job {} to status PENDING to continue execution", job.getId());
            }

            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 150) {
                    step.setStatus(JobStatus.completed);
                    step.setOutput(String.format("https://%s/tfoutput/v1/organization/%s/job/%s/step/%s", this.hostname,
                            job.getOrganization().getId().toString(), job.getId(), step.getId()));
                    stepRepository.save(step);
                    job.setStatus(JobStatus.pending);
                    jobRepository.save(job);
                    try {
                        scheduleJobService.createJobContextNow(job);
                    } catch (SchedulerException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }
            }
        }
        return getRun(runId, null);
    }

    RunsData runDiscard(int runId) {
        try {
            log.warn("Updating job status for discard: {}", runId);
            Job job = jobRepository.getReferenceById(Integer.valueOf(runId));
            job.setStatus(JobStatus.cancelled);
            jobRepository.save(job);
            scheduleJobService.deleteJobContext(job.getId());
        } catch (ParseException | SchedulerException e) {
            log.error(e.getMessage());
        }
        return getRun(runId, null);
    }

    PlanRunData getPlan(int planId) {
        PlanRunData plansData = new PlanRunData();
        PlanRunModel planRunModel = new PlanRunModel();
        planRunModel.setId(String.valueOf(planId));
        planRunModel.setType("plans");
        planRunModel.setAttributes(new HashMap<>());
        String planStatus = "pending";

        Job job = jobRepository.getReferenceById(Integer.valueOf(planId));
        if (job.getStep() != null && !job.getStep().isEmpty()) {
            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 100) {
                    switch (step.getStatus()) {
                        case pending:
                            planStatus = "pending";
                            break;
                        case queue:
                        case running:
                            planStatus = "running";
                            break;
                        case completed:
                            planStatus = "finished";
                            break;
                        case failed:
                            planStatus = "errored";
                            break;
                    }
                }
            }
        }

        HashMap<String, Object> actions = new HashMap<>();
        actions.put("is-confirmable", true);
        actions.put("is-discardable", false);
        planRunModel.getAttributes().put("actions", actions);

        planRunModel.getAttributes().put("status", planStatus);
        String encryptedPlanId = encryptionService.encrypt(String.valueOf(planId));
        log.info("log-read-url: {}", String.format("https://%s/remote/tfe/v2/plans/logs/%s", hostname, encryptedPlanId));
        planRunModel.getAttributes().put("log-read-url",
                String.format("https://%s/remote/tfe/v2/plans/logs/%s", hostname, encryptedPlanId));
        plansData.setData(planRunModel);
        return plansData;
    }

    ApplyRunData getApply(int planId) {
        ApplyRunData applyRunData = new ApplyRunData();
        ApplyRunModel applyModel = new ApplyRunModel();
        applyModel.setId(String.valueOf(planId));
        applyModel.setType("applies");
        applyModel.setAttributes(new HashMap<>());
        String applyStatus = "pending";

        Job job = jobRepository.getReferenceById(Integer.valueOf(planId));
        if (job.getStep() != null && !job.getStep().isEmpty()) {
            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 200) {
                    switch (step.getStatus()) {
                        case pending:
                            applyStatus = "pending";
                            break;
                        case queue:
                        case running:
                            applyStatus = "running";
                            break;
                        case completed:
                            applyStatus = "finished";
                            break;
                        case failed:
                            applyStatus = "errored";
                            break;
                    }
                }
            }
        }
        applyModel.getAttributes().put("status", applyStatus);
        String encryptedPlanId = encryptionService.encrypt(String.valueOf(planId));
        log.info("log-read-url: {}", String.format("https://%s/remote/tfe/v2/applies/logs/%s", hostname, encryptedPlanId));
        applyModel.getAttributes().put("log-read-url",
                String.format("https://%s/remote/tfe/v2/applies/logs/%s", hostname, encryptedPlanId));

        applyRunData.setData(applyModel);
        return applyRunData;
    }

    byte[] getPlanLogs(String planId, int offset, int limit) {
        planId = encryptionService.decrypt(planId);
        log.info("Searching logs for plan {}", planId);
        Optional<Job> job = jobRepository.findById(Integer.valueOf(planId));
        byte[] logs = "".getBytes();
        if(job.isPresent()) {
            TextStringBuilder logsOutput = new TextStringBuilder();
            if (job.get().getStep() != null && !job.get().getStep().isEmpty())
                for (Step step : job.get().getStep()) {
                    if (step.getStepNumber() == 100) {
                        log.info("Checking logs for plan: {}", step.getId());

                        try {
                            @SuppressWarnings("unchecked")
                            List<MapRecord<String, String, String>> messagesPlan = redisTemplate.opsForStream()
                                    .read(StreamOffset.fromStart(String.valueOf(job.get().getId())),
                                            StreamOffset.latest(String.valueOf(job.get().getId())));

                            for (MapRecord<String, String, String> mapRecord : messagesPlan) {
                                Map<String, String> streamData = (Map<String, String>) mapRecord.getValue();
                                logsOutput.appendln(streamData.get("output"));
                            }

                            String logsOutputString = logsOutput.toString();
                            int potentialEndIndex = limit + offset;
                            int endIndex = logsOutputString.length() > potentialEndIndex ? potentialEndIndex
                                    : logsOutputString.length();
                            logs = logsOutputString.substring(offset, endIndex).getBytes(StandardCharsets.UTF_8);
                            log.debug("{}", logs);
                        } catch (Exception ex) {
                            log.debug(ex.getMessage());
                        }
                    }
                }
        } else {
            log.warn("No logs found for plan {}", planId);
        }
        return logs;
    }

    byte[] getApplyLogs(String applyId, int offset, int limit) {
        applyId = encryptionService.decrypt(applyId);
        log.info("Searching logs for apply {}", applyId);
        Optional<Job> job = jobRepository.findById(Integer.valueOf(applyId));
        byte[] logs = "".getBytes();
        if (job.isPresent()) {
            TextStringBuilder logsOutputApply = new TextStringBuilder();
            if (job.get().getStep() != null && !job.get().getStep().isEmpty())
                for (Step step : job.get().getStep()) {
                    if (step.getStepNumber() == 100) {
                        log.debug("Checking logs stepId for apply: {}", step.getId());

                        try {
                            @SuppressWarnings("unchecked")
                            List<MapRecord<String, String, String>> messagesApply = redisTemplate.opsForStream().read(
                                    StreamOffset.fromStart(String.valueOf(job.get().getId())),
                                    StreamOffset.latest(String.valueOf(job.get().getId())));

                            for (MapRecord<String, String, String> mapRecord : messagesApply) {
                                Map<String, String> streamData = (Map<String, String>) mapRecord.getValue();
                                logsOutputApply.appendln(streamData.get("output"));
                            }

                            String logsOutputString = logsOutputApply.toString();
                            int potentialEndIndex = limit + offset;
                            int endIndex = logsOutputString.length() > potentialEndIndex ? potentialEndIndex
                                    : logsOutputString.length();
                            logs = logsOutputString.substring(offset, endIndex).getBytes(StandardCharsets.UTF_8);
                            log.debug("{}", logs);
                        } catch (Exception ex) {
                            log.debug(ex.getMessage());
                        }
                    }
                }
        } else {
            log.warn("No logs found for apply {}", applyId);
        }
        return logs;
    }

    StateOutputs getCurrentOutputs(String workspaceId) {
        StateOutputs stateOutputs = new StateOutputs();
        stateOutputs.setData(new ArrayList());
        try {
            Optional<Workspace> searchWorkspace = workspaceRepository.findById(UUID.fromString(workspaceId));
            if (searchWorkspace.isPresent()) {
                Workspace workspace = searchWorkspace.get();
                byte[] currentState = storageTypeService
                        .getCurrentTerraformState(workspace.getOrganization().getId().toString(), workspaceId);
                String state = new String(currentState, StandardCharsets.UTF_8);
                TerraformState terraformState = new ObjectMapper().readValue(state, TerraformState.class);

                terraformState.getOutputs().forEach((mapKey, mapValue) -> {
                    log.info("Processing Key output: {}", mapKey);

                    OutputData outputData = new OutputData();
                    outputData.setId(UUID.randomUUID().toString());
                    outputData.setType("state-version-outputs");
                    outputData.setAttributes(new HashMap());
                    outputData.getAttributes().put("name", mapKey);

                    LinkedHashMap linkedHashMap = (LinkedHashMap) mapValue;

                    outputData.getAttributes().put("value", linkedHashMap.get("value"));

                    if (linkedHashMap.get("sensitive") != null)
                        outputData.getAttributes().put("sensitive", linkedHashMap.get("sensitive"));
                    else
                        outputData.getAttributes().put("sensitive", false);

                    if (linkedHashMap.get("type") instanceof String) {
                        outputData.getAttributes().put("type", "string");
                        outputData.getAttributes().put("detailed-type", "string");
                    } else {
                        outputData.getAttributes().put("type", "object");
                        outputData.getAttributes().put("detailed-type", linkedHashMap.get("type"));
                    }
                    stateOutputs.getData().add(outputData);

                });
            }
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }

        return stateOutputs;
    }

    public ProjectList searchOrganizationProjects(String organizationName, JwtAuthenticationToken currentUser){
        Organization organization = organizationRepository.getOrganizationByName(organizationName);

        if (organization != null && (validateUserIsMemberOrg(organization, currentUser) || validateUserLimitedWorkspaceAccess(organization, currentUser))) {
            log.info("Searching projects for organization {}", organizationName);
            ProjectList projectList = new ProjectList();
            projectList.setData(new ArrayList());
            organization.getProject().forEach(project -> {
                ProjectModel projectData = new ProjectModel();
                projectData.setId(project.getId().toString());
                projectData.setType("project");
                projectData.setAttributes(new HashMap<>());
                projectData.getAttributes().put("name", project.getName());
                projectData.setRelationships(new io.terrakube.api.plugin.state.model.project.Relationships());
                projectData.getRelationships().setOrganization(new io.terrakube.api.plugin.state.model.project.OrganizationModel());
                Resource organizationResource = new Resource();
                organizationResource.setId(organization.getId().toString());
                organizationResource.setType("organizations");
                projectData.getRelationships().getOrganization().setData(organizationResource);
                projectList.getData().add(projectData);
            });
            return projectList;
        } else {
            return null;
        }
    }

    public ProjectData createOrganizationProject(String organizationName, ProjectData projectData, JwtAuthenticationToken currentUser){
        Organization organization = organizationRepository.getOrganizationByName(organizationName);

        if (organization != null && (validateUserIsMemberOrg(organization, currentUser) || validateUserLimitedWorkspaceAccess(organization, currentUser))) {
            Project project = new Project();
            project.setName(projectData.getData().getAttributes().get("name").toString());
            project.setOrganization(organization);
            project = projectRepository.save(project);
            projectData.getData().setId(project.getId().toString());

            return projectData;
        } else {
            return null;
        }
    }
}
