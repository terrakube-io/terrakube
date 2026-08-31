package io.terrakube.api.plugin.security.authentication.dex;

import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DexAuthenticationManagerResolverTest {

    @Mock
    PatRepository patRepository;

    @Mock
    TeamTokenRepository teamTokenRepository;

    @Mock
    FederatedRepository federatedRepository;

    private HttpServletRequest dexTokenRequest() {
        String jti = UUID.randomUUID().toString();
        String payloadJson = "{\"iss\":\"https://dummy-dex-issuer\",\"aud\":\"terrakube\",\"jti\":\"" + jti + "\"}";
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "header." + payload + ".signature";

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);
        return request;
    }

    private DexAuthenticationManagerResolver newResolver() {
        return DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(Base64.getUrlEncoder().encodeToString("pat-secret-pat-secret-pat-secret".getBytes()))
                .internalJwtSecret(Base64.getUrlEncoder().encodeToString("internal-secret-internal-secret".getBytes()))
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build();
    }

    @Test
    void reusesTheDecoderAcrossRequestsInsteadOfRefetchingIssuerMetadataEveryTime() {
        when(patRepository.findById(any())).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(federatedRepository.findByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(Optional.empty());

        DexAuthenticationManagerResolver resolver = newResolver();
        JwtDecoder decoder = mock(JwtDecoder.class);

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer")).thenReturn(decoder);

            AuthenticationManager first = resolver.resolve(dexTokenRequest());
            AuthenticationManager second = resolver.resolve(dexTokenRequest());

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            jwtDecoders.verify(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"), Mockito.times(1));
        }
    }

    @Test
    void translatesAnUnreachableIssuerIntoAnAuthenticationExceptionInsteadOfALeakingRuntimeException() {
        when(patRepository.findById(any())).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(federatedRepository.findByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(Optional.empty());

        DexAuthenticationManagerResolver resolver = newResolver();

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"))
                    .thenThrow(new IllegalStateException("Connection refused"));

            assertThatThrownBy(() -> resolver.resolve(dexTokenRequest()))
                    .isInstanceOf(AuthenticationServiceException.class);
        }
    }

    @Test
    void retriesOnTheNextRequestAfterAFailedFetchInsteadOfCachingTheFailure() {
        when(patRepository.findById(any())).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(any())).thenReturn(Optional.empty());
        when(federatedRepository.findByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(Optional.empty());

        DexAuthenticationManagerResolver resolver = newResolver();
        JwtDecoder decoder = mock(JwtDecoder.class);

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"))
                    .thenThrow(new IllegalStateException("Connection refused"))
                    .thenReturn(decoder);

            assertThatThrownBy(() -> resolver.resolve(dexTokenRequest()))
                    .isInstanceOf(AuthenticationServiceException.class);

            AuthenticationManager recovered = resolver.resolve(dexTokenRequest());

            assertThat(recovered).isNotNull();
            jwtDecoders.verify(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"), Mockito.times(2));
        }
    }

    private HttpServletRequest mockTokenRequest(String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);
        return request;
    }

    private String createSignedToken(String issuer, String base64Secret) {
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
                .issuedAt(java.util.Date.from(java.time.Instant.now()))
                .expiration(java.util.Date.from(java.time.Instant.now().plus(60, java.time.temporal.ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();
    }

    @Test
    void resolve_patToken_32ByteSecret_supportsHS256() {
        byte[] secretBytes = org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(32).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        DexAuthenticationManagerResolver resolver = DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(secret)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build();

        String token = createSignedToken("Terrakube", secret);
        AuthenticationManager manager = resolver.resolve(mockTokenRequest(token));

        assertThat(manager).isNotNull();
        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertThat(authResult).isNotNull();
        assertThat(authResult.isAuthenticated()).isTrue();
    }

    @Test
    void resolve_patToken_48ByteSecret_supportsHS384() {
        byte[] secretBytes = org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(48).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        DexAuthenticationManagerResolver resolver = DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(secret)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build();

        String token = createSignedToken("Terrakube", secret);
        AuthenticationManager manager = resolver.resolve(mockTokenRequest(token));

        assertThat(manager).isNotNull();
        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertThat(authResult).isNotNull();
        assertThat(authResult.isAuthenticated()).isTrue();
    }

    @Test
    void resolve_patToken_64ByteSecret_supportsHS512() {
        byte[] secretBytes = org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(64).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        DexAuthenticationManagerResolver resolver = DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(secret)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build();

        String token = createSignedToken("Terrakube", secret);
        AuthenticationManager manager = resolver.resolve(mockTokenRequest(token));

        assertThat(manager).isNotNull();
        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertThat(authResult).isNotNull();
        assertThat(authResult.isAuthenticated()).isTrue();
    }

    @Test
    void resolve_internalToken_64ByteSecret_supportsHS512() {
        byte[] secretBytes = org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(64).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        DexAuthenticationManagerResolver resolver = DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(secret)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build();

        String token = createSignedToken("TerrakubeInternal", secret);
        AuthenticationManager manager = resolver.resolve(mockTokenRequest(token));

        assertThat(manager).isNotNull();
        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertThat(authResult).isNotNull();
        assertThat(authResult.isAuthenticated()).isTrue();
    }
}
