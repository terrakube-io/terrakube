package io.terrakube.api.plugin.hardened;

import com.yahoo.elide.core.filter.predicates.FalsePredicate;
import com.yahoo.elide.core.filter.predicates.InPredicate;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.core.security.User;
import com.yahoo.elide.core.type.ClassType;
import io.terrakube.api.plugin.security.authentication.dex.RunnerBasicAuthFilter;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.state.StateService;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.rs.checks.workspace.WorkspaceReadFilter;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HardenedEdgeCasesTest {

    @TempDir
    Path dir;

    @Test
    void runnerTokenHelpersHandleMissingClaims() {
        assertThat(RunnerToken.isRunner(null)).isFalse();
        assertThat(RunnerToken.isRunner(null)).isFalse();
        assertThat(RunnerToken.isRunner(Map.of("iss", "TerrakubeInternal", "workspaceId", "ws"))).isFalse();
        assertThat(RunnerToken.claim(null, "x")).isNull();
        assertThat(RunnerToken.claim(Map.of(), "x")).isNull();
        assertThat(RunnerToken.claim(Map.of("x", 1), "x")).isEqualTo("1");
    }

    @Test
    void pemKeysRejectNonPemFiles() throws Exception {
        Path garbage = Files.writeString(dir.resolve("garbage.pem"), "not a key");
        assertThatThrownBy(() -> PemKeys.readPrivateKey(garbage.toString())).isInstanceOf(java.io.IOException.class);
        Path reversed = Files.writeString(dir.resolve("reversed.pem"), "-----END PUBLIC KEY-----\n-----BEGIN PUBLIC KEY-----");
        assertThatThrownBy(() -> PemKeys.readPublicKey(reversed.toString())).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void decoderValidatorRequiresASingleAudienceAndAnExpiry() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", RunnerToken.ISSUER);
        claims.put("organizationId", "o");
        claims.put("workspaceId", "w");
        claims.put("jobId", "1");
        claims.put("stepId", "s");
        claims.put("flow", "terraformPlan");
        claims.put("hardened", true);

        claims.put("aud", List.of(RunnerToken.AUDIENCE, "other"));
        assertThat(RunnerTokenDecoder.validateAudienceAndScope(jwt(claims, true)).hasErrors()).isTrue();
        claims.put("aud", List.of(RunnerToken.AUDIENCE));
        assertThat(RunnerTokenDecoder.validateAudienceAndScope(jwt(claims, false)).hasErrors()).isTrue();
        assertThat(RunnerTokenDecoder.validateAudienceAndScope(jwt(claims, true)).hasErrors()).isFalse();
        claims.remove("aud");
        assertThat(RunnerTokenDecoder.validateAudienceAndScope(jwt(claims, true)).hasErrors()).isTrue();
    }

    @Test
    void basicFilterExposesTheBearerThroughGetHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/tfstate/v1/runner/plan");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("runner:a.b.c".getBytes(StandardCharsets.UTF_8)));
        request.addHeader("X-Other", "kept");
        MockFilterChain chain = new MockFilterChain();
        new RunnerBasicAuthFilter().doFilter(request, new MockHttpServletResponse(), chain);
        HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();
        assertThat(Collections.list(wrapped.getHeaders("authorization"))).containsExactly("Bearer a.b.c");
        assertThat(Collections.list(wrapped.getHeaders("X-Other"))).containsExactly("kept");
        assertThat(wrapped.getHeader("X-Other")).isEqualTo("kept");
    }

    @Test
    void stateServiceOnlyAcceptsJwtPrincipals() {
        StateService stateService = new StateService();
        UsernamePasswordAuthenticationToken notJwt = new UsernamePasswordAuthenticationToken("u", "p");
        assertThat(stateService.isRunner(notJwt)).isFalse();
        assertThat(stateService.runnerMayReadPlan(notJwt, "w", "1")).isTrue();
        assertThat(stateService.hasManageStatePermission(notJwt, UUID.randomUUID().toString(), UUID.randomUUID().toString())).isFalse();
        assertThat(stateService.hasManageStatePermission(null, "o", "w")).isFalse();
    }

    @Test
    void workspaceReadFilterLimitsRunnersToTheirWorkspace() {
        RequestScope scope = mock(RequestScope.class);
        EntityDictionary dictionary = mock(EntityDictionary.class);
        when(scope.getDictionary()).thenReturn(dictionary);
        when(dictionary.getIdFieldName(ClassType.of(Workspace.class))).thenReturn("id");
        WorkspaceReadFilter filter = new WorkspaceReadFilter(mock(GroupService.class), mock(RbacService.class));

        UUID workspaceId = UUID.randomUUID();
        when(scope.getUser()).thenReturn(new User(new JwtAuthenticationToken(jwt(Map.of("iss", RunnerToken.ISSUER,
                "workspaceId", workspaceId.toString()), true))));
        assertThat(filter.getFilterExpression(ClassType.of(Workspace.class), scope)).isInstanceOf(InPredicate.class);
        Workspace own = new Workspace();
        own.setId(workspaceId);
        assertThat(filter.applyPredicateToObject(own, null, scope)).isTrue();
        Workspace other = new Workspace();
        other.setId(UUID.randomUUID());
        assertThat(filter.applyPredicateToObject(other, null, scope)).isFalse();

        when(scope.getUser()).thenReturn(new User(new JwtAuthenticationToken(jwt(Map.of("iss", RunnerToken.ISSUER,
                "workspaceId", "not-a-uuid"), true))));
        assertThat(filter.getFilterExpression(ClassType.of(Workspace.class), scope)).isInstanceOf(FalsePredicate.class);
    }

    @Test
    void runnerTokensAreServiceAccountsButNeverSuperUsers() {
        io.terrakube.api.plugin.security.user.dex.DexAuthenticatedUserImpl users =
                new io.terrakube.api.plugin.security.user.dex.DexAuthenticatedUserImpl();
        User runner = new User(new JwtAuthenticationToken(jwt(Map.of("iss", RunnerToken.ISSUER, "workspaceId", "w",
                "groups", List.of("TERRAKUBE_ADMIN")), true)));
        assertThat(users.isServiceAccount(runner)).isTrue();
        assertThat(users.isSuperUser(runner)).isFalse();
    }

    private static Jwt jwt(Map<String, Object> claims, boolean expiring) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(claims));
        if (expiring) {
            builder.expiresAt(Instant.now().plusSeconds(60));
        }
        return builder.build();
    }
}
