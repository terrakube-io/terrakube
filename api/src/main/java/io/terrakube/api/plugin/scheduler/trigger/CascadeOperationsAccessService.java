package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.scheduler.reconciliation.SchedulerReconciliationAccessService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.repository.TeamRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Who may operate on cascades and run-trigger events: an instance admin anywhere, or a member of a
 * team with {@code manage_job} in the organization the cascade or event belongs to.
 */
@Service
public class CascadeOperationsAccessService {

    private final SchedulerReconciliationAccessService instanceAdminAccess;
    private final TeamTokenService teamTokenService;
    private final TeamRepository teamRepository;
    private final RbacService rbacService;

    public CascadeOperationsAccessService(SchedulerReconciliationAccessService instanceAdminAccess,
            TeamTokenService teamTokenService, TeamRepository teamRepository, RbacService rbacService) {
        this.instanceAdminAccess = instanceAdminAccess;
        this.teamTokenService = teamTokenService;
        this.teamRepository = teamRepository;
        this.rbacService = rbacService;
    }

    public boolean isInstanceAdmin(Authentication authentication) {
        return instanceAdminAccess.isAdmin(authentication);
    }

    public boolean canOperate(Authentication authentication, UUID organizationId) {
        if (isInstanceAdmin(authentication)) {
            return true;
        }
        if (organizationId == null || !(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        List<String> groups = teamTokenService.getCurrentGroups(token);
        return !groups.isEmpty() && teamRepository.findAllByOrganizationIdAndNameIn(organizationId, groups).stream()
                .anyMatch(rbacService::canManageJob);
    }

    public void requireOperate(Authentication authentication, UUID organizationId) {
        if (!canOperate(authentication, organizationId)) {
            throw new AccessDeniedException("Operating on run cascades requires the manage job permission");
        }
    }

    public void requireInstanceAdmin(Authentication authentication) {
        if (!isInstanceAdmin(authentication)) {
            throw new AccessDeniedException("Instance administrator required");
        }
    }
}
