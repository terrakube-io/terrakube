package io.terrakube.registry.configuration.authentication.dex;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.federated.ClaimsData;
import io.terrakube.client.model.federated.Federated;
import io.terrakube.client.model.federated.FederatedAttributes;
import io.terrakube.client.model.federated.Relationships;
import io.terrakube.client.model.federated.claim.FederatedClaim;
import io.terrakube.client.model.federated.claim.FederatedClaimAttributes;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.registry.service.token.TokenStatusClient;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistryAuthenticationManagerResolverTest {

    @Mock
    private TerrakubeClient terrakubeClient;

    @Mock
    private TokenStatusClient tokenStatusClient;

    @Mock
    private HttpServletRequest request;

    @Mock
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private RegistryAuthenticationManagerResolver resolver;

    private String patSecret;
    private final String issuerUri = "https://dex.example.com";

    @BeforeEach
    void setUp() {
        patSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(RandomStringUtils.secure().nextAlphanumeric(32).getBytes());
        resolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(patSecret)
                .internalSecret(Base64.getEncoder().encodeToString(RandomStringUtils.secure().nextAlphanumeric(32).getBytes()))
                .issuerUri(issuerUri)
                .terrakubeClient(terrakubeClient)
                .tokenStatusClient(tokenStatusClient)
                .jwtDecoderFactory(url -> {
                    if (url.equals(issuerUri)) {
                        throw new IllegalArgumentException("Unable to resolve the Configuration with the provided Issuer of \"" + url + "\"");
                    }
                    return jwtDecoder;
                })
                .build();
    }

    @Test
    void resolve_internalToken_returnsProviderManager() throws Exception {
        Map<String, Object> payload = Map.of("iss", "TerrakubeInternal");
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(payload));

        AuthenticationManager manager = resolver.resolve(request);

        assertNotNull(manager);
        verifyNoInteractions(terrakubeClient);
    }

    @Test
    void resolve_patToken_returnsProviderManager() throws Exception {
        Map<String, Object> payload = Map.of("iss", "Terrakube");
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(payload));

        AuthenticationManager manager = resolver.resolve(request);

        assertNotNull(manager);
        verifyNoInteractions(terrakubeClient);
    }

    @Test
    void resolve_federatedToken_matchingClaims_returnsProviderManagerAndUsesCache() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";

        Map<String, Object> payload = Map.of(
                "iss", issuer,
                "aud", audience,
                "repository", "octocat/hello-world"
        );
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(payload));

        Federated federated = new Federated();
        federated.setId("fed-1");
        FederatedAttributes fedAttrs = new FederatedAttributes();
        fedAttrs.setIssuerUrl(issuer);
        fedAttrs.setAudience(audience);
        federated.setAttributes(fedAttrs);

        FederatedClaim claim = new FederatedClaim();
        claim.setId("claim-1");
        FederatedClaimAttributes claimAttrs = new FederatedClaimAttributes();
        claimAttrs.setClaimKey("repository");
        claimAttrs.setClaimValue("octocat/hello-world");
        claim.setAttributes(claimAttrs);

        ResponseWithInclude<List<Federated>, FederatedClaim> response = federatedResponse(
                List.of(federated), Map.of(federated, List.of(claim)));

        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(response);

        AuthenticationManager manager1 = resolver.resolve(request);
        assertNotNull(manager1);

        // Call a second time - should be served from Caffeine cache
        AuthenticationManager manager2 = resolver.resolve(request);
        assertNotNull(manager2);

        verify(terrakubeClient, times(1)).getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience);
    }

    @Test
    void resolve_federatedToken_matchesAudienceAndClaimCollections() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of(
                "iss", issuer,
                "aud", List.of("other-service", audience),
                "groups_direct", List.of("developers", "platform"))));

        Federated federated = new Federated();
        FederatedAttributes attributes = new FederatedAttributes();
        attributes.setIssuerUrl(issuer);
        attributes.setAudience(audience);
        federated.setAttributes(attributes);

        FederatedClaim claim = new FederatedClaim();
        FederatedClaimAttributes claimAttributes = new FederatedClaimAttributes();
        claimAttributes.setClaimKey("groups_direct");
        claimAttributes.setClaimValue("platform");
        claim.setAttributes(claimAttributes);

        ResponseWithInclude<List<Federated>, FederatedClaim> response = federatedResponse(
                List.of(federated), Map.of(federated, List.of(claim)));
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, "other-service"))
                .thenReturn(new ResponseWithInclude<>());
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(response);

        assertNotNull(resolver.resolve(request));
        verify(terrakubeClient).getFederatedByIssuerUrlAndAudienceWithClaims(issuer, "other-service");
        verify(terrakubeClient).getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience);
    }

    @Test
    void resolve_federatedToken_mismatchedClaims_fallsBackToDefault() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";

        Map<String, Object> payload = Map.of(
                "iss", issuer,
                "aud", audience,
                "repository", "wrong-org/wrong-repo"
        );
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(payload));

        Federated federated = new Federated();
        FederatedAttributes fedAttrs = new FederatedAttributes();
        fedAttrs.setIssuerUrl(issuer);
        fedAttrs.setAudience(audience);
        federated.setAttributes(fedAttrs);

        FederatedClaim claim = new FederatedClaim();
        FederatedClaimAttributes claimAttrs = new FederatedClaimAttributes();
        claimAttrs.setClaimKey("repository");
        claimAttrs.setClaimValue("octocat/hello-world");
        claim.setAttributes(claimAttrs);

        ResponseWithInclude<List<Federated>, FederatedClaim> response = federatedResponse(
                List.of(federated), Map.of(federated, List.of(claim)));

        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(response);

        assertThrows(BadCredentialsException.class, () -> resolver.resolve(request));
    }

    @Test
    void resolve_federatedToken_checksCredentialsIndependently() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of(
                "iss", issuer,
                "aud", audience,
                "sub", "repo:acme/service-b:ref:refs/heads/main")));

        Federated first = federated("fed-1", issuer, audience);
        FederatedClaim firstClaim = claim("claim-1", "repository", "acme/service-a");
        Federated second = federated("fed-2", issuer, audience);
        FederatedClaim secondClaim = claim("claim-2", "sub", "repo:acme/service-b:ref:refs/heads/main");

        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(federatedResponse(
                        List.of(first, second),
                        Map.of(first, List.of(firstClaim), second, List.of(secondClaim))));

        assertNotNull(resolver.resolve(request));
        verify(terrakubeClient).getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience);
    }

    @Test
    void resolve_federatedToken_actsAsItsCredentialNotAsItsGroupsClaim() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of(
                "iss", issuer, "aud", audience, "repository", "acme/infra", "groups", List.of("TERRAKUBE_ADMIN"))));

        Federated federated = federated("fed-1", issuer, audience);
        federated.getAttributes().setName("ci-trigger");
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(federatedResponse(List.of(federated),
                        Map.of(federated, List.of(claim("claim-1", "repository", "acme/infra")))));
        when(jwtDecoder.decode("federated-token")).thenReturn(org.springframework.security.oauth2.jwt.Jwt.withTokenValue("federated-token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("aud", List.of(audience))
                .claim("repository", "acme/infra")
                .claim("groups", List.of("TERRAKUBE_ADMIN"))
                .build());

        org.springframework.security.core.Authentication authentication = resolver.resolve(request).authenticate(
                new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken("federated-token"));

        assertEquals(List.of("ci-trigger"),
                ((org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) authentication)
                        .getTokenAttributes().get("groups"));
    }

    @Test
    void resolve_federatedToken_dropsEmailAndEmailVerifiedClaims() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of(
                "iss", issuer, "aud", audience, "repository", "acme/infra",
                "email", "owner@example.com", "email_verified", true)));

        Federated federated = federated("fed-1", issuer, audience);
        federated.getAttributes().setName("ci-trigger");
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(federatedResponse(List.of(federated),
                        Map.of(federated, List.of(claim("claim-1", "repository", "acme/infra")))));
        when(jwtDecoder.decode("federated-token")).thenReturn(org.springframework.security.oauth2.jwt.Jwt.withTokenValue("federated-token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("aud", List.of(audience))
                .claim("repository", "acme/infra")
                .claim("email", "owner@example.com")
                .claim("email_verified", true)
                .build());

        org.springframework.security.core.Authentication authentication = resolver.resolve(request).authenticate(
                new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken("federated-token"));

        var jwtAuth = (org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) authentication;
        assertNull(jwtAuth.getTokenAttributes().get("email"));
        assertNull(jwtAuth.getTokenAttributes().get("email_verified"));
        assertEquals(List.of("ci-trigger"), jwtAuth.getTokenAttributes().get("groups"));
    }

    @Test
    void resolve_federatedToken_withoutClaimConditions_isDenied() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of(
                "iss", issuer, "aud", audience)));

        Federated federated = federated("fed-1", issuer, audience);
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(federatedResponse(List.of(federated), Map.of(federated, List.of())));

        assertThrows(BadCredentialsException.class, () -> resolver.resolve(request));
    }

    @Test
    void resolve_defaultDexIssuer_skipsFederatedValidation() throws Exception {
        Map<String, Object> payload = Map.of(
                "iss", issuerUri,
                "aud", "terrakube"
        );
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(payload));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(request));
        verifyNoInteractions(terrakubeClient);
    }

    @Test
    void customCacheProperties_areConfiguredAndInitialized() {
        RegistryAuthenticationManagerResolver customResolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret("secret")
                .internalSecret("secret")
                .issuerUri(issuerUri)
                .federatedCacheExpireAfterWrite(15)
                .federatedCacheMaximumSize(500)
                .providerManagerCacheExpireAfterWrite(30)
                .providerManagerCacheMaximumSize(200)
                .build();

        assertEquals(15, customResolver.getFederatedCacheExpireAfterWrite());
        assertEquals(500, customResolver.getFederatedCacheMaximumSize());
        assertEquals(30, customResolver.getProviderManagerCacheExpireAfterWrite());
        assertEquals(200, customResolver.getProviderManagerCacheMaximumSize());
        assertNotNull(customResolver.getFederatedCache());
        assertNotNull(customResolver.getProviderManagerCache());
    }

    private Federated federated(String id, String issuer, String audience) {
        Federated federated = new Federated();
        federated.setId(id);
        FederatedAttributes attributes = new FederatedAttributes();
        attributes.setIssuerUrl(issuer);
        attributes.setAudience(audience);
        federated.setAttributes(attributes);
        return federated;
    }

    private FederatedClaim claim(String id, String key, String value) {
        FederatedClaim claim = new FederatedClaim();
        claim.setId(id);
        FederatedClaimAttributes attributes = new FederatedClaimAttributes();
        attributes.setClaimKey(key);
        attributes.setClaimValue(value);
        claim.setAttributes(attributes);
        return claim;
    }

    private ResponseWithInclude<List<Federated>, FederatedClaim> federatedResponse(
            List<Federated> credentials, Map<Federated, List<FederatedClaim>> claimsByCredential) {
        List<FederatedClaim> included = new ArrayList<>();
        for (Federated credential : credentials) {
            if (credential.getId() == null) {
                credential.setId(UUID.randomUUID().toString());
            }
            List<FederatedClaim> claims = claimsByCredential.getOrDefault(credential, List.of());
            claims.forEach(claim -> {
                if (claim.getId() == null) {
                    claim.setId(UUID.randomUUID().toString());
                }
            });
            ClaimsData claimsData = new ClaimsData();
            claimsData.setData(claims.stream().map(claim -> (Resource) claim).toList());
            Relationships relationships = new Relationships();
            relationships.setClaims(claimsData);
            credential.setRelationships(relationships);
            included.addAll(claims);
        }
        ResponseWithInclude<List<Federated>, FederatedClaim> response = new ResponseWithInclude<>();
        response.setData(credentials);
        response.setIncluded(included);
        return response;
    }

    private String createRealSignedToken(String issuer, String base64Secret) {
        byte[] secretBytes = io.jsonwebtoken.io.Decoders.BASE64URL.decode(base64Secret);
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretBytes);
        return io.jsonwebtoken.Jwts.builder()
                .issuer(issuer)
                .subject("Test Subject")
                .audience().add(issuer).and()
                .id(UUID.randomUUID().toString())
                .claim("email", "test@terrakube.io")
                .claim("email_verified", true)
                .claim("name", "Test User")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();
    }

    @Test
    void resolve_internalToken_32ByteSecret_supportsHS256() {
        byte[] secretBytes = RandomStringUtils.secure().nextAlphanumeric(32).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        RegistryAuthenticationManagerResolver customResolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(secret)
                .internalSecret(secret)
                .issuerUri(issuerUri)
                .build();

        String token = createRealSignedToken("TerrakubeInternal", secret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);

        AuthenticationManager manager = customResolver.resolve(request);
        assertNotNull(manager);

        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
    }

    @Test
    void resolve_internalToken_48ByteSecret_supportsHS384() {
        byte[] secretBytes = RandomStringUtils.secure().nextAlphanumeric(48).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        RegistryAuthenticationManagerResolver customResolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(secret)
                .internalSecret(secret)
                .issuerUri(issuerUri)
                .build();

        String token = createRealSignedToken("TerrakubeInternal", secret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);

        AuthenticationManager manager = customResolver.resolve(request);
        assertNotNull(manager);

        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
    }

    @Test
    void resolve_internalToken_64ByteSecret_supportsHS512() {
        byte[] secretBytes = RandomStringUtils.secure().nextAlphanumeric(64).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        RegistryAuthenticationManagerResolver customResolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(secret)
                .internalSecret(secret)
                .issuerUri(issuerUri)
                .build();

        String token = createRealSignedToken("TerrakubeInternal", secret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);

        AuthenticationManager manager = customResolver.resolve(request);
        assertNotNull(manager);

        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
    }

    @Test
    void resolve_patToken_64ByteSecret_supportsHS512() {
        byte[] secretBytes = RandomStringUtils.secure().nextAlphanumeric(64).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        RegistryAuthenticationManagerResolver customResolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(secret)
                .internalSecret(secret)
                .issuerUri(issuerUri)
                .tokenStatusClient(tokenStatusClient)
                .build();

        String jti = UUID.randomUUID().toString();
        String token = createSignedPatJwt(jti, secret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);
        when(tokenStatusClient.getTokenStatus(jti)).thenReturn(new TokenStatusClient.TokenStatusResponse(true));

        AuthenticationManager manager = customResolver.resolve(request);
        assertNotNull(manager);

        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
    }

    private AuthenticationManager dexManagerWithAudiences(List<String> accepted, List<String> tokenAudiences) throws Exception {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("dex-token")
                .header("alg", "RS256")
                .issuer(issuerUri)
                .subject("alice")
                .audience(tokenAudiences)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
        when(jwtDecoder.decode("dex-token")).thenReturn(jwt);
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of("iss", issuerUri, "aud", "terrakube")));
        return RegistryAuthenticationManagerResolver.builder()
                .issuerUri(issuerUri)
                .dexAudiences(accepted)
                .terrakubeClient(terrakubeClient)
                .jwtDecoderFactory(url -> jwtDecoder)
                .build()
                .resolve(request);
    }

    @Test
    void resolve_dexToken_withAcceptedAudience_authenticates() throws Exception {
        AuthenticationManager manager = dexManagerWithAudiences(List.of("terrakube-app"), List.of("other", "terrakube-app"));

        assertTrue(manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken("dex-token")).isAuthenticated());
    }

    @Test
    void resolve_dexToken_forAnotherClient_isRejectedWhenAudienceValidationIsOn() throws Exception {
        AuthenticationManager manager = dexManagerWithAudiences(List.of("terrakube-app"), List.of("other-client"));

        assertThrows(org.springframework.security.core.AuthenticationException.class, () ->
                manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken("dex-token")));
    }

    @Test
    void resolve_dexToken_forAnotherClient_isAcceptedWhenAudienceValidationIsOff() throws Exception {
        AuthenticationManager manager = dexManagerWithAudiences(List.of(), List.of("other-client"));

        assertTrue(manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken("dex-token")).isAuthenticated());
    }

    @Test
    void resolve_federatedToken_rejectsSignedJwtWhenDecodedClaimsOrAudienceMismatch() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of(
                "iss", issuer, "aud", audience, "repository", "acme/infra")));

        Federated federated = federated("fed-1", issuer, audience);
        federated.getAttributes().setName("ci-trigger");
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(federatedResponse(List.of(federated),
                        Map.of(federated, List.of(claim("claim-1", "repository", "acme/infra")))));

        when(jwtDecoder.decode("mismatched-claim-token")).thenReturn(org.springframework.security.oauth2.jwt.Jwt.withTokenValue("mismatched-claim-token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("aud", List.of(audience))
                .claim("repository", "evil/repo")
                .build());

        AuthenticationManager manager = resolver.resolve(request);
        assertThrows(BadCredentialsException.class, () ->
                manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken("mismatched-claim-token")));

        when(jwtDecoder.decode("mismatched-aud-token")).thenReturn(org.springframework.security.oauth2.jwt.Jwt.withTokenValue("mismatched-aud-token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("aud", List.of("other-audience"))
                .claim("repository", "acme/infra")
                .build());

        assertThrows(BadCredentialsException.class, () ->
                manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken("mismatched-aud-token")));
    }

    @Test
    void defaultCacheProperties_alignProviderManagerTtlWithFederatedTtl() {
        RegistryAuthenticationManagerResolver defaultResolver = RegistryAuthenticationManagerResolver.builder()
                .issuerUri(issuerUri)
                .build();
        assertEquals(10, defaultResolver.getFederatedCacheExpireAfterWrite());
        assertEquals(10, defaultResolver.getProviderManagerCacheExpireAfterWrite());
    }

    @Test
    void resolve_federatedToken_nullClaimValue_failsClosedWithoutNpe() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        when(request.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of(
                "iss", issuer, "aud", audience, "repository", "acme/infra")));

        Federated malformed = federated("fed-bad", issuer, audience);
        malformed.getAttributes().setName("bad-fed");
        FederatedClaim nullValueClaim = claim("claim-null", "repository", null);
        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(federatedResponse(List.of(malformed), Map.of(malformed, List.of(nullValueClaim))));

        assertThrows(BadCredentialsException.class, () -> resolver.resolve(request));
    }

    @Test
    void resolve_federatedToken_delimiterInClaimValues_usesCollisionFreeSha256CacheKey() throws Exception {
        String issuer = "https://token.actions.githubusercontent.com";
        String audience = "terrakube";
        HttpServletRequest req1 = mock(HttpServletRequest.class);
        HttpServletRequest req2 = mock(HttpServletRequest.class);
        when(req1.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of("iss", issuer, "aud", audience, "k", "a|b@c:d=e,f")));
        when(req2.getHeader("authorization")).thenReturn(createMockJwtToken(Map.of("iss", issuer, "aud", audience, "k", "a|b")));

        Federated first = federated("fed-1", issuer, audience);
        first.getAttributes().setName("team-1");
        Federated second = federated("fed-2", issuer, audience);
        second.getAttributes().setName("team-1@terrakube:k=x");

        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(federatedResponse(
                        List.of(first, second),
                        Map.of(
                                first, List.of(claim("c1", "k", "a|b@c:d=e,f")),
                                second, List.of(claim("c2", "k", "a|b"))
                        )));

        AuthenticationManager m1 = resolver.resolve(req1);
        AuthenticationManager m2 = resolver.resolve(req2);
        assertNotSame(m1, m2);
        assertEquals(2, resolver.getProviderManagerCache().asMap().size());
        for (String key : resolver.getProviderManagerCache().asMap().keySet()) {
            assertTrue(key.startsWith("federated:"), "Expected SHA-256 cache key, got: " + key);
            assertFalse(key.contains("a|b"), "Cache key must not contain plaintext claim values");
        }
    }

    @Test
    void acceptedAudiences_combinesClientIdAndExtraAudiences() {
        assertEquals(List.of("terrakube-app", "cli", "ui"), DexWebSecurityAdapter.acceptedAudiences("terrakube-app", " cli, ui ,,terrakube-app"));
        assertEquals(List.of("terrakube-app"), DexWebSecurityAdapter.acceptedAudiences("terrakube-app", ""));
        assertEquals(List.of("terrakube-app"), DexWebSecurityAdapter.acceptedAudiences("terrakube-app", null));
    }

    @Test
    void patToken_active_authenticatesAndCachesRevocationStatus() {
        String jti = UUID.randomUUID().toString();
        String jwt = createSignedPatJwt(jti, patSecret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + jwt);
        when(tokenStatusClient.getTokenStatus(jti)).thenReturn(new TokenStatusClient.TokenStatusResponse(true));

        AuthenticationManager manager = resolver.resolve(request);
        Authentication auth1 = manager.authenticate(new BearerTokenAuthenticationToken(jwt));
        Authentication auth2 = manager.authenticate(new BearerTokenAuthenticationToken(jwt));

        assertTrue(auth1.isAuthenticated());
        assertTrue(auth2.isAuthenticated());
        verify(tokenStatusClient, times(1)).getTokenStatus(jti);
    }

    @Test
    void patToken_revoked_rejectsAndCachesRevokedStatus() {
        String jti = UUID.randomUUID().toString();
        String jwt = createSignedPatJwt(jti, patSecret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + jwt);
        when(tokenStatusClient.getTokenStatus(jti)).thenReturn(new TokenStatusClient.TokenStatusResponse(false));

        AuthenticationManager manager = resolver.resolve(request);
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(jwt)));
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(jwt)));

        verify(tokenStatusClient, times(1)).getTokenStatus(jti);
    }

    @Test
    void patToken_missingOrNonUuidJti_rejectsWithoutCallingApi() {
        String noJtiJwt = createSignedPatJwt(null, patSecret);
        String badJtiJwt = createSignedPatJwt("not-a-uuid", patSecret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + noJtiJwt);

        AuthenticationManager manager = resolver.resolve(request);
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(noJtiJwt)));
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(badJtiJwt)));

        verifyNoInteractions(tokenStatusClient);
    }

    @Test
    void patToken_invalidSignature_rejectsWithoutCallingApiOrPopulatingCache() {
        String jti = UUID.randomUUID().toString();
        String wrongSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(RandomStringUtils.secure().nextAlphanumeric(32).getBytes());
        String badSigJwt = createSignedPatJwt(jti, wrongSecret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + badSigJwt);

        AuthenticationManager manager = resolver.resolve(request);
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(badSigJwt)));

        verifyNoInteractions(tokenStatusClient);
        assertNull(resolver.getPatRevocationCache().getIfPresent(jti));
    }

    @Test
    void patToken_apiFailure_failsClosedWithoutCaching() {
        String jti = UUID.randomUUID().toString();
        String jwt = createSignedPatJwt(jti, patSecret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + jwt);
        when(tokenStatusClient.getTokenStatus(jti))
                .thenThrow(new RuntimeException("API unavailable"))
                .thenReturn(new TokenStatusClient.TokenStatusResponse(true));

        AuthenticationManager manager = resolver.resolve(request);
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(jwt)));
        assertNull(resolver.getPatRevocationCache().getIfPresent(jti));

        Authentication recovered = manager.authenticate(new BearerTokenAuthenticationToken(jwt));
        assertTrue(recovered.isAuthenticated());
        verify(tokenStatusClient, times(2)).getTokenStatus(jti);
    }

    @Test
    void patToken_nullTokenStatusClient_failsClosed() {
        String jti = UUID.randomUUID().toString();
        String jwt = createSignedPatJwt(jti, patSecret);
        RegistryAuthenticationManagerResolver noClientResolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(patSecret)
                .internalSecret(Base64.getEncoder().encodeToString(RandomStringUtils.secure().nextAlphanumeric(32).getBytes()))
                .issuerUri(issuerUri)
                .build();
        when(request.getHeader("authorization")).thenReturn("Bearer " + jwt);

        AuthenticationManager manager = noClientResolver.resolve(request);
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(jwt)));
    }

    @Test
    void patToken_zeroCacheTtl_queriesApiOnEveryRequest() {
        String jti = UUID.randomUUID().toString();
        String jwt = createSignedPatJwt(jti, patSecret);
        RegistryAuthenticationManagerResolver uncachedResolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(patSecret)
                .internalSecret(Base64.getEncoder().encodeToString(RandomStringUtils.secure().nextAlphanumeric(32).getBytes()))
                .issuerUri(issuerUri)
                .tokenStatusClient(tokenStatusClient)
                .patRevocationCacheTtlSeconds(0)
                .build();
        when(request.getHeader("authorization")).thenReturn("Bearer " + jwt);
        when(tokenStatusClient.getTokenStatus(jti))
                .thenReturn(new TokenStatusClient.TokenStatusResponse(true))
                .thenReturn(new TokenStatusClient.TokenStatusResponse(false));

        AuthenticationManager manager = uncachedResolver.resolve(request);
        assertTrue(manager.authenticate(new BearerTokenAuthenticationToken(jwt)).isAuthenticated());
        assertThrows(AuthenticationException.class, () -> manager.authenticate(new BearerTokenAuthenticationToken(jwt)));
        verify(tokenStatusClient, times(2)).getTokenStatus(jti);
    }

    private String createSignedPatJwt(String jti, String base64UrlSecret) {
        var builder = Jwts.builder()
                .issuer("Terrakube")
                .subject("Terrakube")
                .claim("email", "user@example.com")
                .claim("groups", List.of("team-a"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)));
        if (jti != null) {
            builder.id(jti);
        }
        return builder.signWith(Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(base64UrlSecret))).compact();
    }

    private String createMockJwtToken(Map<String, Object> payloadMap) throws Exception {
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = new ObjectMapper().writeValueAsString(payloadMap);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString(headerJson.getBytes()) + "." + encoder.encodeToString(payloadJson.getBytes()) + ".signature";
    }
}
