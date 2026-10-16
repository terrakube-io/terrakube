package io.terrakube.api.plugin.security.state;

import io.terrakube.api.plugin.security.token.RunnerToken;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerStateAccessTest {

    @Mock
    WorkspaceRepository workspaceRepository;
    @Mock
    io.terrakube.api.plugin.hardened.RunnerJobGuard runnerJobGuard;
    @InjectMocks
    StateService stateService;

    UUID org = UUID.randomUUID();
    UUID otherOrg = UUID.randomUUID();
    Workspace own;
    Workspace sharedWithOwn;
    Workspace notShared;
    Workspace global;
    Workspace foreign;

    @BeforeEach
    void setup() {
        when(runnerJobGuard.jobActive(any())).thenReturn(true);
        own = workspace(org, false, null);
        sharedWithOwn = workspace(org, false, "x, " + own.getId());
        notShared = workspace(org, false, UUID.randomUUID().toString());
        global = workspace(org, true, null);
        foreign = workspace(otherOrg, true, null);
    }

    @Test
    void runnerReadsItsOwnAndExplicitlySharedStatesOnly() {
        JwtAuthenticationToken runner = runner(own.getId().toString(), "7");
        assertThat(stateService.hasManageStatePermission(runner, org.toString(), own.getId().toString())).isTrue();
        assertThat(stateService.hasManageStatePermission(runner, org.toString(), sharedWithOwn.getId().toString())).isTrue();
        assertThat(stateService.hasManageStatePermission(runner, org.toString(), global.getId().toString())).isFalse();
        assertThat(stateService.hasManageStatePermission(runner, org.toString(), notShared.getId().toString())).isFalse();
        assertThat(stateService.hasManageStatePermission(runner, otherOrg.toString(), foreign.getId().toString())).isFalse();
        assertThat(stateService.hasManageStatePermission(runner, org.toString(), foreign.getId().toString())).isFalse();
    }

    @Test
    void finishedJobsCannotReadStateAnymore() {
        when(runnerJobGuard.jobActive(any())).thenReturn(false);
        JwtAuthenticationToken runner = runner(own.getId().toString(), "7");
        assertThat(stateService.hasManageStatePermission(runner, org.toString(), own.getId().toString())).isFalse();
    }

    @Test
    void runnerReadsOnlyThePlansOfItsOwnJob() {
        JwtAuthenticationToken runner = runner(own.getId().toString(), "7");
        assertThat(stateService.runnerMayReadPlan(runner, own.getId().toString(), "7")).isTrue();
        assertThat(stateService.runnerMayReadPlan(runner, own.getId().toString(), "8")).isFalse();
        assertThat(stateService.runnerMayReadPlan(runner, global.getId().toString(), "7")).isFalse();
        assertThat(stateService.isRunner(runner)).isTrue();
    }

    private Workspace workspace(UUID organizationId, boolean global, String sharedIds) {
        Organization organization = new Organization();
        organization.setId(organizationId);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setGlobalRemoteState(global);
        workspace.setSharedIds(sharedIds);
        when(workspaceRepository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        return workspace;
    }

    private JwtAuthenticationToken runner(String workspaceId, String jobId) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", RunnerToken.ISSUER)
                .claim(RunnerToken.CLAIM_ORGANIZATION_ID, org.toString())
                .claim(RunnerToken.CLAIM_WORKSPACE_ID, workspaceId)
                .claim(RunnerToken.CLAIM_JOB_ID, jobId)
                .claim(RunnerToken.CLAIM_STEP_ID, "s")
                .claim(RunnerToken.CLAIM_HARDENED, true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
