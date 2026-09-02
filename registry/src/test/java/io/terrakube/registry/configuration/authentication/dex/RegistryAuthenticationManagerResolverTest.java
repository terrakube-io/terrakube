package io.terrakube.registry.configuration.authentication.dex;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.federated.Federated;
import io.terrakube.client.model.federated.FederatedAttributes;
import io.terrakube.client.model.federated.claim.FederatedClaim;
import io.terrakube.client.model.federated.claim.FederatedClaimAttributes;
import io.terrakube.client.model.response.ResponseWithInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistryAuthenticationManagerResolverTest {

    @Mock
    private TerrakubeClient terrakubeClient;

    @Mock
    private HttpServletRequest request;

    @Mock
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private RegistryAuthenticationManagerResolver resolver;

    private final String issuerUri = "https://dex.example.com";

    @BeforeEach
    void setUp() {
        resolver = RegistryAuthenticationManagerResolver.builder()
                .patSecret(Base64.getEncoder().encodeToString(RandomStringUtils.secure().nextAlphanumeric(32).getBytes()))
                .internalSecret(Base64.getEncoder().encodeToString(RandomStringUtils.secure().nextAlphanumeric(32).getBytes()))
                .issuerUri(issuerUri)
                .terrakubeClient(terrakubeClient)
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

        ResponseWithInclude<List<Federated>, FederatedClaim> response = new ResponseWithInclude<>();
        response.setData(List.of(federated));
        response.setIncluded(List.of(claim));

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

        ResponseWithInclude<List<Federated>, FederatedClaim> response = new ResponseWithInclude<>();
        response.setData(List.of(federated));
        response.setIncluded(List.of(claim));

        when(terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience))
                .thenReturn(response);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(request));
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
                .build();

        String token = createRealSignedToken("Terrakube", secret);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);

        AuthenticationManager manager = customResolver.resolve(request);
        assertNotNull(manager);

        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
    }

    private String createMockJwtToken(Map<String, Object> payloadMap) throws Exception {
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = new ObjectMapper().writeValueAsString(payloadMap);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString(headerJson.getBytes()) + "." + encoder.encodeToString(payloadJson.getBytes()) + ".signature";
    }
}
