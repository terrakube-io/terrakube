package io.terrakube.api.plugin.vcs.discovery;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.vcs.Vcs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

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
    VcsRepositoryAccessService subject;

    final UUID orgId = UUID.randomUUID();
    final UUID vcsId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        teamRepository = mock(TeamRepository.class);
        vcsRepository = mock(VcsRepository.class);
        rbacService = mock(RbacService.class);

        subject = new VcsRepositoryAccessService();
        ReflectionTestUtils.setField(subject, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(subject, "vcsRepository", vcsRepository);
        ReflectionTestUtils.setField(subject, "rbacService", rbacService);
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
    void internalIssuerBypassesAllChecks() {
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "TerrakubeInternal"));

        assertThat(subject.hasViewPermission(token, vcsId.toString())).isTrue();
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
