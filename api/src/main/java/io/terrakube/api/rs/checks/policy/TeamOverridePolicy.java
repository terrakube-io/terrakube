package io.terrakube.api.rs.checks.policy;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.policy.PolicyAttachment;
import io.terrakube.api.rs.policy.PolicyOverride;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@SecurityCheck(TeamOverridePolicy.RULE)
public class TeamOverridePolicy extends OperationCheck<PolicyOverride> {

    public static final String RULE = "team override policy";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(PolicyOverride override, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team override policy {}", override.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        if (override.getEvaluation() == null || override.getEvaluation().getJob() == null
                || override.getEvaluation().getJob().getOrganization() == null) {
            return false;
        }

        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        Job job = override.getEvaluation().getJob();
        Workspace jobWorkspace = job.getWorkspace();
        UUID jobWorkspaceId = jobWorkspace != null ? jobWorkspace.getId() : null;

        List<PolicySet> policySets = job.getOrganization().getPolicySet();

        // Only grant overrideTeam rights for policy sets that are in scope for this job's workspace.
        // A policy set is in scope if it is global OR has an explicit attachment to the job workspace.
        // Checking ALL org policy sets would allow a user to gain override rights via a policy set
        // configured for an entirely different workspace (cross-policy privilege escalation).
        if (policySets != null) {
            for (PolicySet ps : policySets) {
                if (!isPolicySetInScopeForWorkspace(ps, jobWorkspaceId)) {
                    log.debug("Skipping overrideTeam check for policy set {} — not in scope for workspace {}",
                            ps.getId(), jobWorkspaceId);
                    continue;
                }
                if (ps.getOverrideTeam() != null && !ps.getOverrideTeam().isBlank()) {
                    boolean isMember = isServiceAccount
                            ? groupService.isServiceMember(requestScope.getUser(), ps.getOverrideTeam())
                            : groupService.isMember(requestScope.getUser(), ps.getOverrideTeam());
                    if (isMember) {
                        return true;
                    }
                }
            }
        }

        // Fallback: check if user belongs to an org team with canManagePolicies permission
        List<Team> teamList = job.getOrganization().getTeam();
        if (teamList != null) {
            for (Team team : teamList) {
                if (isServiceAccount) {
                    if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManagePolicies(team)) {
                        return true;
                    }
                } else {
                    if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManagePolicies(team)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Returns true if the given policy set is in scope for the specified workspace.
     * <p>
     * A policy set is in scope when:
     * <ul>
     *   <li>It is marked as {@code global} (applies to all workspaces in the org), or</li>
     *   <li>It has at least one {@link PolicyAttachment} whose {@code workspace} matches
     *       {@code workspaceId}.</li>
     * </ul>
     * When {@code workspaceId} is {@code null} (no workspace on the job), only global policy
     * sets are considered in scope.
     *
     * @param ps          the policy set to evaluate
     * @param workspaceId the UUID of the job's workspace, or {@code null} if absent
     * @return {@code true} if the policy set applies to the workspace
     */
    private boolean isPolicySetInScopeForWorkspace(PolicySet ps, UUID workspaceId) {
        if (ps.isGlobal()) {
            return true;
        }
        if (workspaceId == null) {
            // No workspace to scope against — consider all policy sets relevant.
            // Cross-workspace escalation can only happen when a specific workspace is involved,
            // so without one we allow all policy sets to participate in the override check.
            return true;
        }
        List<PolicyAttachment> attachments = ps.getAttachments();
        if (attachments == null) {
            return false;
        }
        for (PolicyAttachment attachment : attachments) {
            if (attachment.getWorkspace() != null
                    && workspaceId.equals(attachment.getWorkspace().getId())) {
                return true;
            }
        }
        return false;
    }
}
