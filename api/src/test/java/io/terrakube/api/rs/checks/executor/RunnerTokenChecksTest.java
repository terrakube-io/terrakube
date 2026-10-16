package io.terrakube.api.rs.checks.executor;

import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunnerTokenChecksTest {

    RequestScope scope = mock(RequestScope.class);
    io.terrakube.api.plugin.hardened.RunnerChangePolicy policy;
    Organization organization = new Organization();
    Workspace workspace = new Workspace();
    Job job = new Job();
    Step step = new Step();
    History history = new History();

    @BeforeEach
    void setup() {
        io.terrakube.api.plugin.hardened.RunnerJobGuard guard = mock(io.terrakube.api.plugin.hardened.RunnerJobGuard.class);
        when(guard.jobActive(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(guard.stepStartingOrRunning(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(guard.stepReporting(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        policy = new io.terrakube.api.plugin.hardened.RunnerChangePolicy(guard, "api.example");
        organization.setId(UUID.randomUUID());
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);
        job.setId(42);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        step.setId(UUID.randomUUID());
        step.setJob(job);
        history.setId(UUID.randomUUID());
        history.setWorkspace(workspace);
    }

    private void runner(String workspaceId, String jobId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", RunnerToken.ISSUER);
        claims.put("organizationId", organization.getId().toString());
        claims.put("workspaceId", workspaceId);
        claims.put("jobId", jobId);
        claims.put("stepId", step.getId().toString());
        claims.put("hardened", true);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
        when(scope.getUser()).thenReturn(new User(new JwtAuthenticationToken(jwt)));
    }

    @Test
    void runnerTokensGetTheScopedExecutorRights() {
        runner(workspace.getId().toString(), "42");
        assertTrue(with(new ExecutorReadWorkspace()).ok(workspace, scope, Optional.empty()));
        assertFalse(new ExecutorManageWorkspaceHistory().ok(workspace, scope, Optional.empty()));
        assertTrue(with(new ExecutorReadJob()).ok(job, scope, Optional.empty()));
        assertTrue(with(new ExecutorManageJob()).ok(job, scope, Optional.empty()));
        assertTrue(with(new ExecutorReadJobStep()).ok(step, scope, Optional.empty()));
        assertTrue(with(new ExecutorManageJobStep()).ok(step, scope, Optional.empty()));
        assertTrue(with(new ExecutorReadHistory()).ok(history, scope, Optional.empty()));
        assertFalse(new ExecutorManageHistory().ok(history, scope, Optional.empty()));
        assertTrue(with(new ExecutorReadOrganization()).ok(organization, scope, Optional.empty()));
    }

    @Test
    void runnerTokensForAnotherWorkspaceOrJobAreDenied() {
        runner(UUID.randomUUID().toString(), "42");
        assertFalse(with(new ExecutorReadWorkspace()).ok(workspace, scope, Optional.empty()));
        assertFalse(new ExecutorManageWorkspaceHistory().ok(workspace, scope, Optional.empty()));
        assertFalse(with(new ExecutorManageJob()).ok(job, scope, Optional.empty()));
        assertFalse(new ExecutorManageHistory().ok(history, scope, Optional.empty()));

        runner(workspace.getId().toString(), "43");
        assertFalse(with(new ExecutorManageJob()).ok(job, scope, Optional.empty()));
        assertFalse(with(new ExecutorManageJobStep()).ok(step, scope, Optional.empty()));
    }

    private <T> T with(T check) {
        org.springframework.test.util.ReflectionTestUtils.setField(check, "runnerChangePolicy", policy);
        return check;
    }

    @Test
    void runnersWithoutPolicyOrAfterTheirJobAreDenied() {
        runner(workspace.getId().toString(), "42");
        assertFalse(new ExecutorReadWorkspace().ok(workspace, scope, Optional.empty()));
        assertFalse(new ExecutorManageJob().ok(job, scope, Optional.empty()));
        assertFalse(new ExecutorManageJobStep().ok(step, scope, Optional.empty()));
    }
}
