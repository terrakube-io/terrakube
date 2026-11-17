package io.terrakube.api.plugin.module;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yahoo.elide.core.security.User;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.module.Module;

/** The "team manage module" check (TeamManageModule) for module endpoints outside Elide. */
@Service
public class ModuleAccessService {

    private final ModuleRepository moduleRepository;

    private final MembershipService membershipService;

    private final RbacService rbacService;

    public ModuleAccessService(ModuleRepository moduleRepository, MembershipService membershipService,
            RbacService rbacService) {
        this.moduleRepository = moduleRepository;
        this.membershipService = membershipService;
        this.rbacService = rbacService;
    }

    @Transactional
    public boolean hasManagePermission(Authentication authentication, String orgId, String moduleId) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }
        if (InternalTokenClassifier.classify(jwt.getTokenAttributes()) != InternalTokenType.NOT_INTERNAL) {
            return false;
        }

        UUID id;
        try {
            id = UUID.fromString(moduleId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        Module module = moduleRepository.findById(id).orElse(null);
        // The module must belong to the organization in the path, whose teams are checked below.
        if (module == null || module.getOrganization() == null
                || !module.getOrganization().getId().toString().equals(orgId)) {
            return false;
        }
        return membershipService.checkTeamPermission(new User(jwt), module.getOrganization().getTeam(),
                rbacService::canManageModule);
    }
}
