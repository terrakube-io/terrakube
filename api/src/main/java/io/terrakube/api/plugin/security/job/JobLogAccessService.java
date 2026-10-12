package io.terrakube.api.plugin.security.job;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class JobLogAccessService {

    public enum LogAccessResult {
        ALLOWED,
        NOT_FOUND,
        FORBIDDEN
    }

    private final JobRepository jobRepository;
    private final StepRepository stepRepository;
    private final TeamRepository teamRepository;
    private final RbacService rbacService;
    private final String instanceOwner;

    public JobLogAccessService(
            JobRepository jobRepository,
            StepRepository stepRepository,
            TeamRepository teamRepository,
            RbacService rbacService,
            @Value("${io.terrakube.owner:}") String instanceOwner) {
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.teamRepository = teamRepository;
        this.rbacService = rbacService;
        this.instanceOwner = instanceOwner;
    }

    @Transactional(readOnly = true)
    public LogAccessResult checkAccess(Authentication authentication, String organizationId, String jobId, String stepId) {
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return LogAccessResult.FORBIDDEN;
        }

        // Validate jobId format
        int intJobId;
        try {
            intJobId = Integer.parseInt(jobId);
        } catch (NumberFormatException e) {
            log.debug("Invalid jobId format: {}", jobId);
            return LogAccessResult.NOT_FOUND;
        }

        Job job = jobRepository.findById(intJobId).orElse(null);
        if (job == null) {
            log.debug("Job not found: {}", intJobId);
            return LogAccessResult.NOT_FOUND;
        }

        // Validate organization hierarchy
        if (job.getOrganization() == null || !job.getOrganization().getId().toString().equals(organizationId)) {
            log.warn("Job {} organization does not match provided organizationId {}", intJobId, organizationId);
            return LogAccessResult.NOT_FOUND;
        }

        // Validate step hierarchy if stepId provided
        if (stepId != null && !stepId.isBlank()) {
            UUID stepUuid;
            try {
                stepUuid = UUID.fromString(stepId);
            } catch (IllegalArgumentException e) {
                log.debug("Invalid stepId UUID format: {}", stepId);
                return LogAccessResult.NOT_FOUND;
            }

            Step step = stepRepository.findById(stepUuid).orElse(null);
            if (step == null || step.getJob() == null || step.getJob().getId() != intJobId) {
                log.warn("Step {} not found or does not belong to job {}", stepId, intJobId);
                return LogAccessResult.NOT_FOUND;
            }
        }

        // Internal service token has full access if genuine system token, or scoped if executor token
        String iss = (String) jwt.getTokenAttributes().get("iss");
        if ("TerrakubeInternal".equals(iss)) {
            Object tokenJobId = jwt.getTokenAttributes().get("jobId");
            if (tokenJobId != null && !String.valueOf(intJobId).equals(String.valueOf(tokenJobId))) {
                log.warn("TerrakubeInternal token jobId {} does not match requested job {}", tokenJobId, intJobId);
                return LogAccessResult.FORBIDDEN;
            }
            Object tokenWorkspaceId = jwt.getTokenAttributes().get("workspaceId");
            if (tokenWorkspaceId != null && (job.getWorkspace() == null || !job.getWorkspace().getId().toString().equals(String.valueOf(tokenWorkspaceId)))) {
                log.warn("TerrakubeInternal token workspaceId {} does not match job workspace", tokenWorkspaceId);
                return LogAccessResult.FORBIDDEN;
            }
            return LogAccessResult.ALLOWED;
        }

        List<String> groups = extractGroups(jwt);

        // Instance superuser has access across all organizations and jobs
        if (instanceOwner != null && !instanceOwner.isBlank() && groups.contains(instanceOwner)) {
            return LogAccessResult.ALLOWED;
        }

        // 1. Organization level check: any team member in the organization has read access to jobs
        List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(job.getOrganization().getId(), groups);
        if (!teams.isEmpty()) {
            return LogAccessResult.ALLOWED;
        }

        // 2. Workspace level check
        Workspace workspace = job.getWorkspace();
        if (workspace != null) {
            List<Access> accessList = workspace.getAccess();
            if (accessList != null) {
                for (Access access : accessList) {
                    if (groups.contains(access.getName())) {
                        return LogAccessResult.ALLOWED;
                    }
                }
            }

            // 3. Project level check
            Project project = workspace.getProject();
            if (project != null && project.getProjectAccess() != null) {
                for (ProjectAccess pa : project.getProjectAccess()) {
                    if (groups.contains(pa.getName())) {
                        return LogAccessResult.ALLOWED;
                    }
                }
            }
        }

        log.warn("User with groups {} denied access to job {} in organization {}", groups, intJobId, organizationId);
        return LogAccessResult.FORBIDDEN;
    }

    @Transactional(readOnly = true)
    public LogAccessResult checkJobAccess(Authentication authentication, int jobId) {
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return LogAccessResult.FORBIDDEN;
        }

        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.debug("Job not found: {}", jobId);
            return LogAccessResult.NOT_FOUND;
        }

        // Internal service token has full access if genuine system token, or scoped if executor token
        String iss = (String) jwt.getTokenAttributes().get("iss");
        if ("TerrakubeInternal".equals(iss)) {
            Object tokenJobId = jwt.getTokenAttributes().get("jobId");
            if (tokenJobId != null && !String.valueOf(jobId).equals(String.valueOf(tokenJobId))) {
                log.warn("TerrakubeInternal token jobId {} does not match requested job {}", tokenJobId, jobId);
                return LogAccessResult.FORBIDDEN;
            }
            Object tokenWorkspaceId = jwt.getTokenAttributes().get("workspaceId");
            if (tokenWorkspaceId != null && (job.getWorkspace() == null || !job.getWorkspace().getId().toString().equals(String.valueOf(tokenWorkspaceId)))) {
                log.warn("TerrakubeInternal token workspaceId {} does not match job workspace", tokenWorkspaceId);
                return LogAccessResult.FORBIDDEN;
            }
            return LogAccessResult.ALLOWED;
        }

        List<String> groups = extractGroups(jwt);

        // Instance superuser has access across all organizations and jobs
        if (instanceOwner != null && !instanceOwner.isBlank()) {
            Object email = jwt.getTokenAttributes().get("email");
            if (instanceOwner.equals(email) || groups.contains(instanceOwner)) {
                return LogAccessResult.ALLOWED;
            }
        }

        // 1. Organization level check
        if (job.getOrganization() != null) {
            List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(job.getOrganization().getId(), groups);
            if (!teams.isEmpty()) {
                return LogAccessResult.ALLOWED;
            }
        }

        // 2. Workspace level check
        Workspace workspace = job.getWorkspace();
        if (workspace != null) {
            List<Access> accessList = workspace.getAccess();
            if (accessList != null) {
                for (Access access : accessList) {
                    if (groups.contains(access.getName())) {
                        return LogAccessResult.ALLOWED;
                    }
                }
            }

            // 3. Project level check
            Project project = workspace.getProject();
            if (project != null && project.getProjectAccess() != null) {
                for (ProjectAccess pa : project.getProjectAccess()) {
                    if (groups.contains(pa.getName())) {
                        return LogAccessResult.ALLOWED;
                    }
                }
            }
        }

        log.warn("User with groups {} denied access to job {}", groups, jobId);
        return LogAccessResult.FORBIDDEN;
    }

    @Transactional(readOnly = true)
    public boolean canWriteContext(Authentication authentication, int jobId) {
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        String iss = (String) jwt.getTokenAttributes().get("iss");
        if ("TerrakubeInternal".equals(iss)) {
            Object tokenJobId = jwt.getTokenAttributes().get("jobId");
            if (tokenJobId != null) {
                return String.valueOf(jobId).equals(String.valueOf(tokenJobId));
            }
            return true;
        }

        List<String> groups = extractGroups(jwt);
        if (instanceOwner != null && !instanceOwner.isBlank()) {
            Object email = jwt.getTokenAttributes().get("email");
            if (instanceOwner.equals(email) || groups.contains(instanceOwner)) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGroups(JwtAuthenticationToken jwt) {
        Object groupsObj = jwt.getTokenAttributes().get("groups");
        if (groupsObj instanceof List<?>) {
            return (List<String>) groupsObj;
        } else if (groupsObj instanceof String groupStr) {
            return List.of(groupStr);
        }
        return Collections.emptyList();
    }
}
