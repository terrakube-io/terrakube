package io.terrakube.api.plugin.security.job;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final GroupService groupService;
    private final AuthenticatedUser authenticatedUser;
    private final String instanceOwner;

    public JobLogAccessService(
            JobRepository jobRepository,
            StepRepository stepRepository,
            TeamRepository teamRepository,
            GroupService groupService,
            AuthenticatedUser authenticatedUser,
            @Value("${io.terrakube.owner:}") String instanceOwner) {
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.teamRepository = teamRepository;
        this.groupService = groupService;
        this.authenticatedUser = authenticatedUser;
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

        return authorize(jwt, job);
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

        return authorize(jwt, job);
    }

    // Who may read a job's context and logs, once the job (and the step) were found and belong together.
    private LogAccessResult authorize(JwtAuthenticationToken jwt, Job job) {
        Map<String, Object> claims = jwt.getTokenAttributes();
        InternalTokenType tokenType = InternalTokenClassifier.classify(claims);
        if (tokenType != InternalTokenType.NOT_INTERNAL) {
            return authorizeInternal(tokenType, claims, job);
        }

        User user = new User(jwt);
        List<String> groups = effectiveGroups(user);

        // Instance superuser has access across all organizations and jobs
        if (isInstanceOwner(user, claims, groups)) {
            return LogAccessResult.ALLOWED;
        }

        if (groups.isEmpty()) {
            return denied(claims, groups, job);
        }

        // 1. Organization level check: any team member in the organization has read access to jobs
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

        return denied(claims, groups, job);
    }

    // Internal tokens are told apart by what they are (InternalTokenClassifier), never by the issuer alone:
    // * the executor service token reads and writes the context of any job (it is what the executor daemon
    //   sends, with no job or workspace of its own);
    // * a runner token is for one job of one workspace and has to say which job;
    // * every other internal token (registry, unrecognised, legacy) has no access to a job's logs or context.
    private LogAccessResult authorizeInternal(InternalTokenType tokenType, Map<String, Object> claims, Job job) {
        if (tokenType == InternalTokenType.EXECUTOR_SERVICE) {
            return LogAccessResult.ALLOWED;
        }
        if (tokenType == InternalTokenType.WORKSPACE_EXECUTOR) {
            Object tokenJobId = claims.get("jobId");
            if (tokenJobId == null || !String.valueOf(job.getId()).equals(String.valueOf(tokenJobId))) {
                log.warn("Runner token for job {} denied access to job {}", tokenJobId, job.getId());
                return LogAccessResult.FORBIDDEN;
            }
            Object tokenWorkspaceId = claims.get("workspaceId");
            if (job.getWorkspace() == null || !job.getWorkspace().getId().toString().equals(String.valueOf(tokenWorkspaceId))) {
                log.warn("Runner token workspaceId {} does not match the workspace of job {}", tokenWorkspaceId, job.getId());
                return LogAccessResult.FORBIDDEN;
            }
            return LogAccessResult.ALLOWED;
        }
        log.warn("Internal token ({}, sub={}) denied access to job {}", tokenType, claims.get("sub"), job.getId());
        return LogAccessResult.FORBIDDEN;
    }

    @Transactional(readOnly = true)
    public boolean canWriteContext(Authentication authentication, int jobId) {
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        Map<String, Object> claims = jwt.getTokenAttributes();
        InternalTokenType tokenType = InternalTokenClassifier.classify(claims);
        if (tokenType == InternalTokenType.EXECUTOR_SERVICE) {
            return true;
        }
        if (tokenType == InternalTokenType.WORKSPACE_EXECUTOR) {
            Object tokenJobId = claims.get("jobId");
            return tokenJobId != null && String.valueOf(jobId).equals(String.valueOf(tokenJobId));
        }
        if (tokenType != InternalTokenType.NOT_INTERNAL) {
            return false;
        }

        // A federated identity (GitHub Actions and the like) can read a job through its team but never
        // writes its context, whatever claims the other provider put in the token.
        User user = new User(jwt);
        if (authenticatedUser.isFederatedAccount(user)) {
            return false;
        }

        // The instance owner, by the groups of the token itself or by email.
        return isInstanceOwner(user, claims, extractGroups(claims));
    }

    // The groups the Elide checks use: the token's own groups plus the team of every federated credential the
    // token matches. A GitHub OIDC token has no groups claim, so reading the claim alone denied an identity that
    // JSON:API had already authorized for the same job.
    private List<String> effectiveGroups(User user) {
        return new ArrayList<>(groupService.getEffectiveGroups(user));
    }

    // The owner is a group, or an email for installations that name the owner that way. The email counts only for
    // a token that is not matched by a federated credential: the other provider decides what its tokens say.
    private boolean isInstanceOwner(User user, Map<String, Object> claims, List<String> groups) {
        if (instanceOwner == null || instanceOwner.isBlank()) {
            return false;
        }
        if (groups.contains(instanceOwner)) {
            return true;
        }
        return instanceOwner.equals(claims.get("email")) && !authenticatedUser.isFederatedAccount(user);
    }

    private LogAccessResult denied(Map<String, Object> claims, List<String> groups, Job job) {
        log.warn("Identity (iss={}, sub={}, groups={}) denied access to job {}", claims.get("iss"), claims.get("sub"), groups, job.getId());
        return LogAccessResult.FORBIDDEN;
    }

    // Token groups only, for the check that must not see federated teams.
    @SuppressWarnings("unchecked")
    private List<String> extractGroups(Map<String, Object> claims) {
        Object groupsObj = claims.get("groups");
        if (groupsObj instanceof List<?>) {
            return (List<String>) groupsObj;
        } else if (groupsObj instanceof String groupStr) {
            return List.of(groupStr);
        }
        return Collections.emptyList();
    }
}
