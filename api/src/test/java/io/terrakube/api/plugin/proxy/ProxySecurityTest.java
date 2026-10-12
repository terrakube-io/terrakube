package io.terrakube.api.plugin.proxy;

import io.terrakube.api.plugin.notification.sender.DestinationUrlValidator;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.parameters.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxySecurityTest {

    private WorkspaceRepository workspaceRepository;
    private VariableRepository variableRepository;
    private GlobalVarRepository globalVarRepository;
    private DestinationUrlValidator destinationUrlValidator;
    private TeamRepository teamRepository;
    private RbacService rbacService;

    private ProxyService proxyService;
    private ProxyAccessService proxyAccessService;

    private static final String INSTANCE_OWNER = "TERRAKUBE_ADMIN";

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        variableRepository = mock(VariableRepository.class);
        globalVarRepository = mock(GlobalVarRepository.class);
        destinationUrlValidator = mock(DestinationUrlValidator.class);
        teamRepository = mock(TeamRepository.class);
        rbacService = mock(RbacService.class);

        proxyService = new ProxyService(workspaceRepository, variableRepository, globalVarRepository, destinationUrlValidator);
        proxyAccessService = new ProxyAccessService(workspaceRepository, teamRepository, rbacService, INSTANCE_OWNER);
    }

    private JwtAuthenticationToken createAuthToken(List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user@example.com")
                .claim("groups", groups)
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    void testAccessDeniedForNonMember() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        workspace.setOrganization(org);

        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(org.getId()), anyList())).thenReturn(Collections.emptyList());

        JwtAuthenticationToken auth = createAuthToken(List.of("OTHER_TEAM"));
        assertFalse(proxyAccessService.hasProxyPermission(auth, workspaceId));
    }

    @Test
    void testAccessAllowedForOrgAdmin() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        workspace.setOrganization(org);

        Team team = new Team();
        team.setName("DEV_TEAM");
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(org.getId()), eq(List.of("DEV_TEAM"))))
                .thenReturn(List.of(team));
        when(rbacService.canManageWorkspace(team)).thenReturn(true);

        JwtAuthenticationToken auth = createAuthToken(List.of("DEV_TEAM"));
        assertTrue(proxyAccessService.hasProxyPermission(auth, workspaceId));
    }

    @Test
    void testAccessAllowedForWorkspaceAccessTeam() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        workspace.setOrganization(org);

        Access access = new Access();
        access.setName("WORKSPACE_TEAM");
        workspace.setAccess(List.of(access));

        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(org.getId()), anyList())).thenReturn(Collections.emptyList());
        when(rbacService.canManageWorkspace(access)).thenReturn(true);

        JwtAuthenticationToken auth = createAuthToken(List.of("WORKSPACE_TEAM"));
        assertTrue(proxyAccessService.hasProxyPermission(auth, workspaceId));
    }

    @Test
    void testAccessAllowedForInstanceSuperuser() {
        UUID workspaceId = UUID.randomUUID();
        JwtAuthenticationToken auth = createAuthToken(List.of(INSTANCE_OWNER));
        assertTrue(proxyAccessService.hasProxyPermission(auth, workspaceId));
    }

    @Test
    void testSensitiveVariableInUrlIsStrictlyBlocked() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        Organization org = new Organization();
        workspace.setOrganization(org);

        Variable sensitiveVar = new Variable();
        sensitiveVar.setKey("SECRET_KEY");
        sensitiveVar.setValue("sensitive-token-12345");
        sensitiveVar.setSensitive(true);

        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(List.of(sensitiveVar)));

        RequestEntity<String> request = RequestEntity.method(HttpMethod.GET, URI.create("http://localhost/proxy/v1")).body("");
        ResponseEntity<String> response = proxyService.proxyRequest(
                request,
                "https://api.external.com/test?token={{var.SECRET_KEY}}",
                null,
                workspaceId
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("strictly forbidden"));
        verifyNoInteractions(destinationUrlValidator);
    }

    @Test
    void testNonSensitiveVariableInUrlIsAllowed() {
        Map<String, ProxyService.ResolvedVar> vars = Map.of(
                "REGION", new ProxyService.ResolvedVar("us-east-1", false)
        );

        String result = proxyService.replaceVarsInUrl("https://api.external.com/{{var.REGION}}/deploy", vars);
        assertEquals("https://api.external.com/us-east-1/deploy", result);
    }

    @Test
    void testSensitiveVariableAllowedInPayload() {
        Map<String, ProxyService.ResolvedVar> vars = Map.of(
                "SECRET_KEY", new ProxyService.ResolvedVar("my-secret-token", true)
        );

        String result = proxyService.replaceVars("{\"token\":\"{{var.SECRET_KEY}}\"}", vars);
        assertEquals("{\"token\":\"my-secret-token\"}", result);
    }

    @Test
    void testSsrfDestinationBlocked() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        Organization org = new Organization();
        workspace.setOrganization(org);

        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(variableRepository.findByWorkspace(workspace)).thenReturn(Optional.of(Collections.emptyList()));
        doThrow(new RuntimeException("Blocked by policy")).when(destinationUrlValidator).validate("proxy", "http://169.254.169.254/latest/meta-data");

        RequestEntity<String> request = RequestEntity.method(HttpMethod.GET, URI.create("http://localhost/proxy/v1")).body("");
        ResponseEntity<String> response = proxyService.proxyRequest(
                request,
                "http://169.254.169.254/latest/meta-data",
                null,
                workspaceId
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Target URL destination is not allowed", response.getBody());
    }
}
