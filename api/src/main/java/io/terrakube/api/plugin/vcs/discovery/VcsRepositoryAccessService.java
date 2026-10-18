package io.terrakube.api.plugin.vcs.discovery;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.vcs.Vcs;

@Service
public class VcsRepositoryAccessService {

    private final TeamRepository teamRepository;

    private final VcsRepository vcsRepository;

    private final RbacService rbacService;

    public VcsRepositoryAccessService(TeamRepository teamRepository, VcsRepository vcsRepository,
            RbacService rbacService) {
        this.teamRepository = teamRepository;
        this.vcsRepository = vcsRepository;
        this.rbacService = rbacService;
    }

    @Transactional
    public boolean hasViewPermission(Authentication authentication, String vcsId) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        InternalTokenType tokenType = InternalTokenClassifier.classify(jwt.getTokenAttributes());
        if (tokenType == InternalTokenType.EXECUTOR_SERVICE) {
            return true;
        } else if (tokenType != InternalTokenType.NOT_INTERNAL) {
            return false;
        }

        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId)).orElse(null);
        if (vcs == null || vcs.getOrganization() == null) {
            return false;
        }

        Object groupNames = jwt.getTokenAttributes().get("groups");
        if (groupNames == null) {
            return false;
        }

        @SuppressWarnings("unchecked")
        List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(vcs.getOrganization().getId(),
                (List<String>) groupNames);
        for (Team team : teams) {
            if (rbacService.canManageVcs(team) || rbacService.canManageWorkspace(team)) {
                return true;
            }
        }
        return false;
    }
}
