package io.terrakube.api.plugin.security.state;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StateServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JobRepository jobRepository;
    private StateService stateService;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository = mock(JobRepository.class);
        stateService = new StateService();
        // StateService uses field injection; set the only collaborator this path needs.
        var field = StateService.class.getDeclaredField("jobRepository");
        field.setAccessible(true);
        field.set(stateService, jobRepository);
    }

    /** Internal-issuer token short-circuits hasManageStatePermission to true. */
    private JwtAuthenticationToken internalToken() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").claim("iss", "TerrakubeInternal").build();
        return new JwtAuthenticationToken(jwt);
    }

    private Job jobInOrg(String organizationId) {
        Organization organization = new Organization();
        organization.setId(UUID.fromString(organizationId));
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setOrganization(organization);
        Job job = new Job();
        job.setWorkspace(workspace);
        return job;
    }

    @Test
    void returnsFalseWhenJobIdNotNumeric() {
        assertFalse(stateService.hasManageStatePermissionByJob(internalToken(), ORG, "not-a-number"));
    }

    @Test
    void returnsFalseWhenJobNotFound() {
        when(jobRepository.findById(42)).thenReturn(Optional.empty());
        assertFalse(stateService.hasManageStatePermissionByJob(internalToken(), ORG, "42"));
    }

    @Test
    void returnsFalseWhenJobBelongsToAnotherOrg() {
        // Job resolves to a workspace in a DIFFERENT org than the path — must be rejected
        // even for an internal token, so a job id can't be used to cross org boundaries.
        when(jobRepository.findById(7)).thenReturn(Optional.of(jobInOrg("99999999-9999-9999-9999-999999999999")));
        assertFalse(stateService.hasManageStatePermissionByJob(internalToken(), ORG, "7"));
    }

    @Test
    void returnsTrueForInternalTokenWhenJobMatchesOrg() {
        when(jobRepository.findById(7)).thenReturn(Optional.of(jobInOrg(ORG)));
        assertTrue(stateService.hasManageStatePermissionByJob(internalToken(), ORG, "7"));
    }
}
