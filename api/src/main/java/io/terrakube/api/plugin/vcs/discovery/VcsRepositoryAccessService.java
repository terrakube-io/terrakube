package io.terrakube.api.plugin.vcs.discovery;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.vcs.Vcs;

@Service
public class VcsRepositoryAccessService {

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private VcsRepository vcsRepository;

    @Autowired
    private RbacService rbacService;

    @Transactional
    public boolean hasViewPermission(Authentication authentication, String vcsId) {
        if (((JwtAuthenticationToken) authentication).getTokenAttributes().get("iss").equals("TerrakubeInternal")) {
            return true;
        }

        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId)).orElse(null);
        if (vcs == null || vcs.getOrganization() == null) {
            return false;
        }

        Object groupNames = ((JwtAuthenticationToken) authentication).getTokenAttributes().get("groups");
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
