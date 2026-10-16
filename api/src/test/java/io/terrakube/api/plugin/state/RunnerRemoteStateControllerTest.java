package io.terrakube.api.plugin.state;

import io.terrakube.api.plugin.state.model.outputs.StateOutputs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunnerRemoteStateControllerTest {

    RemoteTfeService service = mock(RemoteTfeService.class);
    RemoteTfeController controller = new RemoteTfeController(service);
    JwtAuthenticationToken runner = new JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none")
            .claim("iss", "TerrakubeRunner").claim("organizationId", "o").claim("workspaceId", "consumer").build());

    @Test
    void runnersOnlyReadStateTheyAreAllowedTo() throws Exception {
        when(service.runnerCanReadState(any(), eq("foreign"))).thenReturn(false);
        assertThat(controller.getCurrentWorkspaceState("foreign", runner).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.getCurrentOutputs("foreign", runner).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).getCurrentWorkspaceState("foreign");
        verify(service, never()).getCurrentOutputs("foreign");
    }

    @Test
    void runnersReadTheirOwnState() throws Exception {
        when(service.runnerCanReadState(any(), eq("consumer"))).thenReturn(true);
        when(service.validateWorkspaceIdTokenCanAccessState("consumer", "consumer")).thenReturn(true);
        when(service.getCurrentOutputs("consumer")).thenReturn(new StateOutputs());
        controller.getCurrentWorkspaceState("consumer", runner);
        assertThat(controller.getCurrentOutputs("consumer", runner).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).getCurrentWorkspaceState("consumer");
    }
}
