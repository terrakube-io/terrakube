package io.terrakube.api.plugin.vcs.discovery;

import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.plugin.security.groups.dex.DexGroupServiceImpl;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.vcs.Vcs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VcsRepositoryAccessServiceTest {

    TeamRepository teamRepository;
    VcsRepository vcsRepository;
    RbacService rbacService;
    FederatedLookupService federatedLookupService;
    VcsRepositoryAccessService subject;

    final UUID orgId = UUID.randomUUID();
    final UUID vcsId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        teamRepository = mock(TeamRepository.class);
        vcsRepository = mock(VcsRepository.class);
        rbacService = mock(RbacService.class);
        federatedLookupService = mock(FederatedLookupService.class);

        subject = new VcsRepositoryAccessService(teamRepository, vcsRepository, rbacService,
                new DexGroupServiceImpl(null, null, null, federatedLookupService));
    }

    private JwtAuthenticationToken authWithClaims(Map<String, Object> claims) {
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getTokenAttributes()).thenReturn(claims);
        return token;
    }

    private Vcs vcsWithOrg() {
        Organization org = new Organization();
        org.setId(orgId);
        Vcs vcs = new Vcs();
        vcs.setId(vcsId);
        vcs.setOrganization(org);
        return vcs;
    }

    @Test
    void executorServiceAllowed() {
        JwtAuthenticationToken token = authWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isTrue();
    }

    @Test
    void workspaceExecutorDenied() {
        JwtAuthenticationToken token = authWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)",
                "workspaceId", UUID.randomUUID().toString()
        ));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isFalse();
    }

    @Test
    void unknownInternalDenied() {
        JwtAuthenticationToken token = authWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isFalse();
    }

    @Test
    void returnsFalseWhenVcsNotFound() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.empty());
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isFalse();
    }

    @Test
    void returnsFalseWhenNoGroupsClaim() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcsWithOrg()));
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube"));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isFalse();
    }

    @Test
    void federatedTokenIgnoresGroupsClaim() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcsWithOrg()));
        Team adminTeam = new Team();
        adminTeam.setRole("admin");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), eq(List.of("team-a")))).thenReturn(List.of(adminTeam));
        when(rbacService.canManageVcs(adminTeam)).thenReturn(true);
        // The external identity provider decides the groups claim; the token acts as its credential only
        Federated federated = new Federated();
        federated.setName("ci-team");
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of(federated));

        JwtAuthenticationToken token = authWithClaims(Map.of(
                "iss", "https://token.actions.githubusercontent.com",
                "groups", List.of("team-a")));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isFalse();
    }

    @Test
    void returnsFalseWhenNoTeamGrantsAccess() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcsWithOrg()));
        Team readOnlyTeam = new Team();
        readOnlyTeam.setRole("read");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of(readOnlyTeam));
        when(rbacService.canManageVcs(readOnlyTeam)).thenReturn(false);
        when(rbacService.canManageWorkspace(readOnlyTeam)).thenReturn(false);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isFalse();
    }

    @Test
    void returnsTrueWhenTeamCanManageWorkspace() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcsWithOrg()));
        Team writeTeam = new Team();
        writeTeam.setRole("write");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of(writeTeam));
        when(rbacService.canManageWorkspace(writeTeam)).thenReturn(true);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isTrue();
    }

    @Test
    void returnsTrueWhenTeamCanManageVcs() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcsWithOrg()));
        Team adminTeam = new Team();
        adminTeam.setRole("admin");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of(adminTeam));
        when(rbacService.canManageVcs(adminTeam)).thenReturn(true);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isTrue();
    }
}
