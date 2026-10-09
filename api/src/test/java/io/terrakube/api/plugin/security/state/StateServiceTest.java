package io.terrakube.api.plugin.security.state;

import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StateServiceTest {

    @Mock
    WorkspaceRepository workspaceRepository;

    @InjectMocks
    StateService subject;

    final Organization org = organization();
    final Organization otherOrg = organization();
    Workspace job;
    Workspace target;

    @BeforeEach
    void setup() {
        job = workspace(org);
        target = workspace(org);
    }

    @Test
    void jobReadsItsOwnState() {
        assertThat(subject.canJobReadState(id(job), id(job))).isTrue();
    }

    @Test
    void jobReadsGlobalStateOnlyInItsOrganization() {
        target.setGlobalRemoteState(true);
        assertThat(subject.canJobReadState(id(job), id(target))).isTrue();

        target.setOrganization(otherOrg);
        assertThat(subject.canJobReadState(id(job), id(target))).isFalse();
    }

    @Test
    void jobReadsStateSharedWithItsWorkspace() {
        target.setGlobalRemoteState(false);
        target.setSharedIds(null);
        assertThat(subject.canJobReadState(id(job), id(target))).isFalse();

        target.setSharedIds(UUID.randomUUID() + ", " + id(job));
        assertThat(subject.canJobReadState(id(job), id(target))).isTrue();

        target.setOrganization(otherOrg);
        assertThat(subject.canJobReadState(id(job), id(target))).isFalse();
    }

    @Test
    void jobTokenNeverManagesState() {
        JwtAuthenticationToken jobToken = token(Map.of("iss", "TerrakubeInternal", "workspaceId", id(job)));
        assertThat(subject.hasManageStatePermission(jobToken, org.getId().toString(), id(job))).isFalse();
        assertThat(subject.hasReadStatePermission(jobToken, org.getId().toString(), id(job))).isTrue();
    }

    @Test
    void jobTokenReadMustMatchTheWorkspaceOrganization() {
        JwtAuthenticationToken jobToken = token(Map.of("iss", "TerrakubeInternal", "workspaceId", id(job)));
        assertThat(subject.hasReadStatePermission(jobToken, otherOrg.getId().toString(), id(job))).isFalse();
    }

    @Test
    void serviceTokenManagesAnyState() {
        JwtAuthenticationToken serviceToken = token(Map.of("iss", "TerrakubeInternal"));
        assertThat(subject.hasManageStatePermission(serviceToken, org.getId().toString(), id(target))).isTrue();
    }

    private Workspace workspace(Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setGlobalRemoteState(false);
        when(workspaceRepository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        return workspace;
    }

    private static Organization organization() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        return organization;
    }

    private static String id(Workspace workspace) {
        return workspace.getId().toString();
    }

    private static JwtAuthenticationToken token(Map<String, Object> claims) {
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getTokenAttributes()).thenReturn(claims);
        return token;
    }
}
