package io.terrakube.api;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.dex.DexGroupServiceImpl;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.federated.claim.FederatedClaim;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DexGroupServiceTests {

    private static final String ISSUER = "https://token.actions.githubusercontent.com";
    private static final String AUDIENCE = "terrakube";
    private static final String GROUP = "ci-trigger";

    @Test
    void federatedTokenWithoutGroupsClaimIsMember() {
        DexGroupServiceImpl groupService = groupServiceWith(federated(GROUP, Map.of("repository", "acme/infra")));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/infra"));

        assertTrue(groupService.isServiceMember(user, GROUP));
    }

    @Test
    void federatedTokenWithClaimMismatchIsNotMember() {
        DexGroupServiceImpl groupService = groupServiceWith(federated(GROUP, Map.of("repository", "acme/infra")));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "someone-else/infra"));

        assertFalse(groupService.isServiceMember(user, GROUP));
    }

    @Test
    void tokenWithGroupsClaimIsStillMember() {
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(Optional.empty());
        DexGroupServiceImpl groupService = new DexGroupServiceImpl(null, null, null, federatedRepository);

        User user = userWith(Map.of(
                "iss", "https://dex.example.com",
                "aud", "terrakube",
                "groups", new java.util.ArrayList<>(List.of("TERRAKUBE_ADMIN"))));

        assertTrue(groupService.isServiceMember(user, "TERRAKUBE_ADMIN"));
        assertFalse(groupService.isServiceMember(user, "other-group"));
    }

    private DexGroupServiceImpl groupServiceWith(Federated federated) {
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(Optional.of(federated));
        return new DexGroupServiceImpl(null, null, null, federatedRepository);
    }

    private Federated federated(String name, Map<String, String> claims) {
        Federated federated = new Federated();
        federated.setName(name);
        federated.setIssuerUrl(ISSUER);
        federated.setAudience(AUDIENCE);
        federated.setClaims(claims.entrySet().stream().map(entry -> {
            FederatedClaim claim = new FederatedClaim();
            claim.setClaimKey(entry.getKey());
            claim.setClaimValue(entry.getValue());
            return claim;
        }).toList());
        return federated;
    }

    private User userWith(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claims(jwtClaims -> jwtClaims.putAll(claims))
                .build();
        return new User(new JwtAuthenticationToken(jwt));
    }
}
