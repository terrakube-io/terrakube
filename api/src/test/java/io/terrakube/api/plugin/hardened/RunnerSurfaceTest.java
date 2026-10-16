package io.terrakube.api.plugin.hardened;

import io.terrakube.api.plugin.logs.LogsController;
import io.terrakube.api.plugin.logs.LogsService;
import io.terrakube.api.plugin.security.authentication.dex.RunnerEndpointFilter;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.plugin.state.model.logs.Log;
import io.terrakube.api.plugin.state.model.logs.LogsRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RunnerSurfaceTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static JwtAuthenticationToken token(String issuer) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "RS256")
                .claims(c -> c.putAll(Map.of("iss", issuer, "jobId", "7", "workspaceId", "w"))).build());
    }

    private int status(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RunnerEndpointFilter().doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void runnerTokensOnlyReachTheRunnerSurface() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(token(RunnerToken.ISSUER));
        for (String[] allowed : new String[][]{
                {"POST", "/tfstate/v1/runner/state"}, {"GET", "/tfstate/v1/runner/configuration"},
                {"GET", "/tfstate/v1/organization/o/workspace/w/state/terraform.tfstate"},
                {"GET", "/tfstate/v1/organization/o/workspace/w/jobId/7/step/s/terraform.tfstate"},
                {"GET", "/context/v1/7"}, {"POST", "/context/v1/7"}, {"POST", "/logs"}, {"POST", "/logs/7/setup-consumer-groups"},
                {"GET", "/api/v1/organization/o/workspace/w"}, {"GET", "/api/v1/organization/o/job/7"},
                {"PATCH", "/api/v1/organization/o/job/7"}, {"PATCH", "/api/v1/organization/o/job/7/step/s"},
                {"GET", "/remote/tfe/v2/ping"}, {"GET", "/remote/tfe/v2/organizations/o/entitlement-set"},
                {"GET", "/remote/tfe/v2/organizations/o/workspaces/w"}, {"GET", "/remote/tfe/v2/workspaces/w/current-state-version"},
                {"GET", "/remote/tfe/v2/workspaces/w/current-state-version-outputs"}}) {
            assertThat(status(allowed[0], allowed[1])).as(allowed[1]).isEqualTo(200);
        }
        for (String[] denied : new String[][]{
                {"POST", "/pat/v1"}, {"POST", "/access-token/v1/teams"}, {"POST", "/graphql/api/v1"},
                {"GET", "/api/v1/organization/o/workspace"}, {"PATCH", "/api/v1/organization/o/workspace/w"},
                {"POST", "/api/v1/organization/o/workspace/w/history"}, {"POST", "/api/v1/organization/o/workspace/w/variable"},
                {"POST", "/remote/tfe/v2/organizations/o/projects"}, {"GET", "/remote/tfe/v2/runs/1"},
                {"GET", "/remote/tfe/v2/configuration-versions/x/terraformContent.tar.gz"},
                {"PUT", "/tfstate/v1/organization/o/workspace/w/rollback/x.json"},
                {"DELETE", "/tfstate/v1/lock/organization/o/workspace/w"}, {"GET", "/admin/v1/scheduler/reconciliation"}}) {
            assertThat(status(denied[0], denied[1])).as(denied[1]).isEqualTo(403);
        }
    }

    @Test
    void otherPrincipalsAreNotAffected() throws Exception {
        assertThat(status("POST", "/pat/v1")).isEqualTo(200);
        SecurityContextHolder.getContext().setAuthentication(token("https://dex"));
        assertThat(status("POST", "/pat/v1")).isEqualTo(200);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", "p"));
        assertThat(status("POST", "/pat/v1")).isEqualTo(200);
    }

    @Test
    void runnerLogsAreLimitedToTheirJob() {
        LogsService service = mock(LogsService.class);
        LogsController controller = new LogsController(service);
        LogsRequest request = new LogsRequest();
        Log own = new Log();
        own.setJobId(7);
        Log other = new Log();
        other.setJobId(8);

        request.setData(List.of(own));
        assertThat(controller.appendLogs(request, token(RunnerToken.ISSUER)).getStatusCode()).isEqualTo(HttpStatus.OK);
        request.setData(List.of(own, other));
        assertThat(controller.appendLogs(request, token(RunnerToken.ISSUER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.appendLogs(request, token("TerrakubeInternal")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.appendLogs(request, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(controller.setupConsumerGroups("7", token(RunnerToken.ISSUER)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.setupConsumerGroups("8", token(RunnerToken.ISSUER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, times(3)).appendLogs(anyList());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void adminsCanForceUnlockAWorkspace() {
        RedisTemplate redis = mock(RedisTemplate.class);
        String workspaceId = UUID.randomUUID().toString();
        assertThat(new RunnerLockAdminController(redis).forceUnlock("o", workspaceId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(redis).delete("terrakube:runner-state-lock:" + workspaceId);
        assertThatThrownBy(() -> new RunnerLockAdminController(redis).forceUnlock("o", "../x")).isInstanceOf(IllegalArgumentException.class);
    }
}
