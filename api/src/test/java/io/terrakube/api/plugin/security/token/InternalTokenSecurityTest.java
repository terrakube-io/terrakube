package io.terrakube.api.plugin.security.token;

import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.rs.checks.ssh.ReadPrivateKey;
import io.terrakube.api.rs.checks.user.IsExecutorService;
import io.terrakube.api.rs.checks.user.IsRegistryService;
import io.terrakube.api.rs.checks.vcs.ReadAccessToken;
import io.terrakube.api.rs.checks.vcs.ReadGitHubAppInstallationToken;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.GitHubAppToken;
import io.terrakube.api.rs.vcs.Vcs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTokenSecurityTest {

    @Mock
    private RequestScope requestScope;

    private User createUserWithClaims(Map<String, Object> claims) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                claims
        );
        return new User(new JwtAuthenticationToken(jwt));
    }

    @Test
    void testClassify_NullOrEmptyClaims_ReturnsExternal() {
        assertEquals(InternalTokenType.NOT_INTERNAL, InternalTokenClassifier.classify(null));
        assertEquals(InternalTokenType.NOT_INTERNAL, InternalTokenClassifier.classify(Collections.emptyMap()));
        assertEquals(InternalTokenType.NOT_INTERNAL, InternalTokenClassifier.classify(Map.of("iss", "https://dex.example.com")));
    }

    @Test
    void testClassify_ExecutorServiceToken() {
        Map<String, Object> claims = Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        );
        assertEquals(InternalTokenType.EXECUTOR_SERVICE, InternalTokenClassifier.classify(claims));
    }

    @Test
    void testClassify_RegistryServiceToken() {
        Map<String, Object> claims = Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        );
        assertEquals(InternalTokenType.REGISTRY_SERVICE, InternalTokenClassifier.classify(claims));
    }

    @Test
    void testClassify_LegacyInternalTokenWithoutWorkspaceId_ReturnsUnknownInternal() {
        Map<String, Object> claims = Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        );
        assertEquals(InternalTokenType.UNKNOWN_INTERNAL, InternalTokenClassifier.classify(claims));
    }

    @Test
    void testClassify_WorkspaceScopedToken() {
        Map<String, Object> claims = Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString()
        );
        assertEquals(InternalTokenType.WORKSPACE_EXECUTOR, InternalTokenClassifier.classify(claims));
    }

    @Test
    void testIsExecutorService_Check() {
        IsExecutorService check = new IsExecutorService();

        User executorUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));
        assertTrue(check.ok(executorUser));

        User workspaceScopedUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString()
        ));
        assertFalse(check.ok(workspaceScopedUser));

        User registryUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        ));
        assertFalse(check.ok(registryUser));

        User legacyInternalUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));
        assertFalse(check.ok(legacyInternalUser));

        User externalUser = createUserWithClaims(Map.of(
                "iss", "https://dex.example.com"
        ));
        assertFalse(check.ok(externalUser));
        assertFalse(check.ok(null));
    }

    @Test
    void testIsRegistryService_Check() {
        IsRegistryService check = new IsRegistryService();

        User registryUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        ));
        assertTrue(check.ok(registryUser));

        User executorUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));
        assertFalse(check.ok(executorUser));

        User workspaceScopedUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString()
        ));
        assertFalse(check.ok(workspaceScopedUser));

        User legacyInternalUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));
        assertFalse(check.ok(legacyInternalUser));

        User externalUser = createUserWithClaims(Map.of(
                "iss", "https://dex.example.com"
        ));
        assertFalse(check.ok(externalUser));
        assertFalse(check.ok(null));
    }

    @Test
    void testReadPrivateKey_OnlyAllowedForRegistryService() {
        ReadPrivateKey check = new ReadPrivateKey();
        Ssh ssh = new Ssh();

        User registryUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        ));
        when(requestScope.getUser()).thenReturn(registryUser);
        assertTrue(check.ok(ssh, requestScope, Optional.empty()));

        User executorUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));
        when(requestScope.getUser()).thenReturn(executorUser);
        assertFalse(check.ok(ssh, requestScope, Optional.empty()));

        User legacyUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));
        when(requestScope.getUser()).thenReturn(legacyUser);
        assertFalse(check.ok(ssh, requestScope, Optional.empty()));
    }

    @Test
    void testReadAccessToken_OnlyAllowedForRegistryService() {
        ReadAccessToken check = new ReadAccessToken();
        Vcs vcs = new Vcs();

        User registryUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        ));
        when(requestScope.getUser()).thenReturn(registryUser);
        assertTrue(check.ok(vcs, requestScope, Optional.empty()));

        User executorUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));
        when(requestScope.getUser()).thenReturn(executorUser);
        assertFalse(check.ok(vcs, requestScope, Optional.empty()));
    }

    @Test
    void testReadGitHubAppInstallationToken_OnlyAllowedForRegistryService() {
        ReadGitHubAppInstallationToken check = new ReadGitHubAppInstallationToken();
        GitHubAppToken token = new GitHubAppToken();

        User registryUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (REGISTRY)"
        ));
        when(requestScope.getUser()).thenReturn(registryUser);
        assertTrue(check.ok(token, requestScope, Optional.empty()));

        User executorUser = createUserWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));
        when(requestScope.getUser()).thenReturn(executorUser);
        assertFalse(check.ok(token, requestScope, Optional.empty()));
    }
}
