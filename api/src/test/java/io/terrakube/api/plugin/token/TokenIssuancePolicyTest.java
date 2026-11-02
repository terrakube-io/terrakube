package io.terrakube.api.plugin.token;

import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.token.pat.Pat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenIssuancePolicyTest {

    private static final String DEX_ISSUER = "https://dex.example.com/dex";

    @Mock
    private FederatedLookupService federatedLookupService;

    @Mock
    private PatRepository patRepository;

    private JwtAuthenticationToken auth(String issuer, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put("iss", issuer);
        claims.put("email", "alice@example.com");
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "none"), claims);
        return new JwtAuthenticationToken(jwt);
    }

    private JwtAuthenticationToken pat(UUID id, Instant expiresAt) {
        Jwt.Builder builder = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .issuer("Terrakube")
                .jti(id.toString())
                .issuedAt(Instant.now())
                .claim("email", "alice@example.com");
        if (expiresAt != null) {
            builder.expiresAt(expiresAt);
        }
        return new JwtAuthenticationToken(builder.build());
    }

    private TokenIssuancePolicy policy(int maxDays) {
        return new TokenIssuancePolicy(DEX_ISSUER, maxDays, federatedLookupService, false, patRepository);
    }

    private TokenIssuancePolicy patIssuancePolicy() {
        return new TokenIssuancePolicy(DEX_ISSUER, 0, federatedLookupService, true, patRepository);
    }

    private UUID storedPat(boolean deleted) {
        UUID id = UUID.randomUUID();
        Pat pat = new Pat();
        pat.setDeleted(deleted);
        when(patRepository.findById(id)).thenReturn(Optional.of(pat));
        return id;
    }

    @Test
    void dexUserCanCreateTokens() {
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> policy(0).requireInteractiveUser(auth(DEX_ISSUER, Map.of())));
    }

    @Test
    void patOrTeamTokenCannotCreateTokens() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy(0).requireInteractiveUser(auth("Terrakube", Map.of())));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void internalTokenCannotCreateTokens() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy(0).requireInteractiveUser(auth("TerrakubeInternal", Map.of("workspaceId", "ws"))));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void federatedTokenFromAnotherIssuerCannotCreateTokens() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy(0).requireInteractiveUser(auth("https://token.actions.githubusercontent.com", Map.of())));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void federatedTokenFromDexIssuerCannotCreateTokens() {
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of(new Federated()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy(0).requireInteractiveUser(auth(DEX_ISSUER, Map.of())));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void missingPrincipalCannotCreateTokens() {
        assertThrows(ResponseStatusException.class, () -> policy(0).requireInteractiveUser(null));
    }

    @Test
    void unlimitedLifetimeIsAllowedWithoutMaximum() {
        assertDoesNotThrow(() -> policy(0).requireLifetimeWithinLimit(0, 0, 0));
        assertDoesNotThrow(() -> policy(0).requireLifetimeWithinLimit(3650, 0, 0));
    }

    @Test
    void negativeLifetimeIsRejectedEvenWithoutMaximum() {
        assertThrows(ResponseStatusException.class, () -> policy(0).requireLifetimeWithinLimit(-1, 0, 0));
        assertThrows(ResponseStatusException.class, () -> policy(0).requireLifetimeWithinLimit(0, -1, 0));
        assertThrows(ResponseStatusException.class, () -> policy(0).requireLifetimeWithinLimit(0, 0, -1));
    }

    @Test
    void maximumLifetimeIsEnforced() {
        TokenIssuancePolicy policy = policy(30);

        assertDoesNotThrow(() -> policy.requireLifetimeWithinLimit(30, 0, 0));
        assertDoesNotThrow(() -> policy.requireLifetimeWithinLimit(0, 1, 0));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> policy.requireLifetimeWithinLimit(0, 0, 0)).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> policy.requireLifetimeWithinLimit(30, 0, 1));
        assertThrows(ResponseStatusException.class, () -> policy.requireLifetimeWithinLimit(31, 0, 0));
        assertThrows(ResponseStatusException.class, () -> policy.requireLifetimeWithinLimit(-1, 0, 0));
        assertThrows(ResponseStatusException.class, () -> policy.requireLifetimeWithinLimit(1, -1, 0));
    }

    @Test
    void patCannotCreateTokensByDefault() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy(0).requireAllowedIssuer(pat(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30))), 1, 0, 0));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void dexUserCanCreateTokensWhenPatIssuanceEnabled() {
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> patIssuancePolicy().requireAllowedIssuer(auth(DEX_ISSUER, Map.of()), 0, 0, 0));
    }

    @Test
    void patCanCreateTokensExpiringWithinItsOwnExpiry() {
        UUID id = storedPat(false);
        JwtAuthenticationToken pat = pat(id, Instant.now().plus(Duration.ofDays(30)));

        assertDoesNotThrow(() -> patIssuancePolicy().requireAllowedIssuer(pat, 29, 0, 0));
        assertDoesNotThrow(() -> patIssuancePolicy().requireAllowedIssuer(pat, 0, 1, 30));
    }

    @Test
    void patCannotCreateTokensOutlivingItself() {
        UUID id = storedPat(false);
        JwtAuthenticationToken pat = pat(id, Instant.now().plus(Duration.ofDays(30)));
        TokenIssuancePolicy policy = patIssuancePolicy();

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> policy.requireAllowedIssuer(pat, 30, 0, 1)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> policy.requireAllowedIssuer(pat, 0, 0, 0)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> policy.requireAllowedIssuer(pat, 1, -1, 0)).getStatusCode());
    }

    @Test
    void patWithoutExpiryCannotCreateTokens() {
        UUID id = storedPat(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> patIssuancePolicy().requireAllowedIssuer(pat(id, null), 1, 0, 0));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void teamTokenCannotCreateTokensWhenPatIssuanceEnabled() {
        UUID id = UUID.randomUUID();
        when(patRepository.findById(id)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> patIssuancePolicy().requireAllowedIssuer(pat(id, Instant.now().plus(Duration.ofDays(30))), 1, 0, 0));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void revokedPatCannotCreateTokens() {
        UUID id = storedPat(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> patIssuancePolicy().requireAllowedIssuer(pat(id, Instant.now().plus(Duration.ofDays(30))), 1, 0, 0));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void federatedTokenWithPatIssuerCannotCreateTokens() {
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of(new Federated()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> patIssuancePolicy().requireAllowedIssuer(pat(UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30))), 1, 0, 0));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void internalTokenCannotCreateTokensWhenPatIssuanceEnabled() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> patIssuancePolicy().requireAllowedIssuer(auth("TerrakubeInternal", Map.of("workspaceId", "ws")), 1, 0, 0));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
