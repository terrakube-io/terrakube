package io.terrakube.api;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.plugin.security.groups.dex.DexGroupServiceImpl;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.federated.claim.FederatedClaim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the security properties of the request-scoped federated memo. The lookup is reached from
 * permission checks, so a caching mistake here is an authorization bug rather than a slow response.
 */
class FederatedLookupServiceTests {

    private static final String ISSUER = "https://token.actions.githubusercontent.com";
    private static final String AUDIENCE = "terrakube";
    private static final String GROUP = "ci-trigger";

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * The scenario the memo must never enable: two identities share an issuer and audience, so they
     * share a cache key, but only one satisfies the provider's claim constraints.
     */
    @Test
    void sharedCacheKeyStillEvaluatesClaimsPerUser() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE))
                .thenReturn(List.of(federated(GROUP, Map.of("repository", "acme/infra"))));
        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, null, null, new FederatedLookupService(repository));

        bindRequest();

        assertTrue(groupService.isFederatedMember(userWith("acme/infra"), GROUP),
                "the identity matching the provider's claims must be a member");
        assertFalse(groupService.isFederatedMember(userWith("attacker/infra"), GROUP),
                "a different identity sharing the issuer and audience must not inherit that verdict");

        verify(repository, times(1)).findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);
    }

    @Test
    void memoDoesNotOutliveTheRequest() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE))
                .thenReturn(List.of(federated(GROUP, Map.of("repository", "acme/infra"))));
        FederatedLookupService service = new FederatedLookupService(repository);

        bindRequest();
        service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);
        service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);
        verify(repository, times(1)).findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);

        bindRequest();
        service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);
        verify(repository, times(2)).findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);
    }

    @Test
    void distinctIssuerAudiencePairsDoNotShareAnEntry() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE))
                .thenReturn(List.of(federated(GROUP, Map.of("repository", "acme/infra"))));
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, "other-audience"))
                .thenReturn(List.of());
        FederatedLookupService service = new FederatedLookupService(repository);

        bindRequest();

        assertFalse(service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE).isEmpty());
        assertTrue(service.findAllByIssuerUrlAndAudience(ISSUER, "other-audience").isEmpty());
    }

    /**
     * The memo derives its request attribute name by joining issuer and audience, so pairs like
     * ("a", "b#c") and ("a#b", "c") flatten to the same name. The stored pair must be re-checked on
     * read, or one would be served the other's provider.
     */
    @Test
    void pairsThatFlattenToTheSameSlotDoNotShareAnAnswer() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience("a", "b#c"))
                .thenReturn(List.of(federated(GROUP, Map.of("repository", "acme/infra"))));
        when(repository.findAllByIssuerUrlAndAudience("a#b", "c")).thenReturn(List.of());
        FederatedLookupService service = new FederatedLookupService(repository);

        bindRequest();

        assertFalse(service.findAllByIssuerUrlAndAudience("a", "b#c").isEmpty());
        assertTrue(service.findAllByIssuerUrlAndAudience("a#b", "c").isEmpty(),
                "a colliding slot name must not hand back the other pair's provider");
    }

    @Test
    void resolvesWithoutARequestContext() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE))
                .thenReturn(List.of(federated(GROUP, Map.of("repository", "acme/infra"))));
        FederatedLookupService service = new FederatedLookupService(repository);

        assertFalse(service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE).isEmpty());
        assertFalse(service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE).isEmpty());
        verify(repository, times(2)).findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);
    }

    /** A miss must be memoized too — unauthenticated issuers are the common case on the hot path. */
    @Test
    void absentProviderIsMemoizedAsWell() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of());
        FederatedLookupService service = new FederatedLookupService(repository);

        bindRequest();

        assertEquals(List.of(), service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE));
        assertEquals(List.of(), service.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE));
        verify(repository, times(1)).findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE);
    }

    @Test
    void authorizedLookupChecksEveryAudienceAndClaimConditions() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, "unrelated")).thenReturn(List.of());
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE))
                .thenReturn(List.of(federated(GROUP, Map.of("repository", "acme/infra"))));
        FederatedLookupService service = new FederatedLookupService(repository);

        assertTrue(service.findAuthorized(Map.of(
                "iss", ISSUER,
                "aud", List.of("unrelated", AUDIENCE),
                "repository", Set.of("acme/infra", "acme/app"))).isPresent());
        assertFalse(service.findAuthorized(Map.of(
                "iss", ISSUER,
                "aud", List.of("unrelated", AUDIENCE),
                "repository", "attacker/infra")).isPresent());
    }

    @Test
    void authorizedLookupChecksEveryCredentialForTheSameIssuerAndAudience() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of(
                federated("team-a", Map.of("repository", "acme/service-a")),
                federated("team-b", Map.of("repository", "acme/service-b"))));
        FederatedLookupService service = new FederatedLookupService(repository);

        Optional<Federated> authorized = service.findAuthorized(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/service-b"));

        assertTrue(authorized.isPresent());
        assertEquals("team-b", authorized.get().getName());
    }

    @Test
    void authorizedLookupDeniesCredentialsWithoutClaimConditions() {
        FederatedRepository repository = mock(FederatedRepository.class);
        when(repository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE))
                .thenReturn(List.of(federated(GROUP, Map.of())));
        FederatedLookupService service = new FederatedLookupService(repository);

        assertFalse(service.findAuthorized(Map.of("iss", ISSUER, "aud", AUDIENCE)).isPresent());
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
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

    private User userWith(String repository) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claims(claims -> claims.putAll(Map.of(
                        "iss", ISSUER,
                        "aud", AUDIENCE,
                        "repository", repository)))
                .build();
        return new User(new JwtAuthenticationToken(jwt));
    }
}
