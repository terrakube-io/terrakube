package io.terrakube.api.plugin.security.authentication.dex;

import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.federated.claim.FederatedClaim;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.token.group.Group;
import io.terrakube.api.rs.token.pat.Pat;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
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

    @Mock
    StepRepository stepRepository;

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
                .stepRepository(stepRepository)
                .build();
    }

    @Test
    void reusesTheDecoderAcrossRequestsInsteadOfRefetchingIssuerMetadataEveryTime() {
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());

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
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());

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
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());

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

    @Test
    void checksAllCredentialsForTheSameIssuerAndAudience() {
        Federated first = federated("team-a", "repository", "acme/service-a");
        Federated second = federated("team-b", "repository", "acme/service-b");
        when(federatedRepository.findAllByIssuerUrlAndAudience(
                "https://token.actions.githubusercontent.com", "terrakube"))
                .thenReturn(List.of(first, second));
        JwtDecoder decoder = mock(JwtDecoder.class);

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://token.actions.githubusercontent.com"))
                    .thenReturn(decoder);

            AuthenticationManager manager = newResolver().resolve(tokenRequest(
                    "{\"iss\":\"https://token.actions.githubusercontent.com\","
                            + "\"aud\":\"terrakube\",\"repository\":\"acme/service-b\"}"));

            assertThat(manager).isNotNull();
        }
    }

    @Test
    void rejectsFederatedCredentialWithoutClaimConditions() {
        Federated federated = new Federated();
        federated.setIssuerUrl("https://token.actions.githubusercontent.com");
        federated.setAudience("terrakube");
        federated.setClaims(List.of());
        when(federatedRepository.findAllByIssuerUrlAndAudience(
                "https://token.actions.githubusercontent.com", "terrakube"))
                .thenReturn(List.of(federated));

        assertThatThrownBy(() -> newResolver().resolve(tokenRequest(
                "{\"iss\":\"https://token.actions.githubusercontent.com\",\"aud\":\"terrakube\"}")))
                .isInstanceOf(BadCredentialsException.class);
    }

    private Federated federated(String name, String claimKey, String claimValue) {
        Federated federated = new Federated();
        federated.setName(name);
        federated.setIssuerUrl("https://token.actions.githubusercontent.com");
        federated.setAudience("terrakube");
        FederatedClaim claim = new FederatedClaim();
        claim.setClaimKey(claimKey);
        claim.setClaimValue(claimValue);
        federated.setClaims(List.of(claim));
        return federated;
    }

    private HttpServletRequest tokenRequest(String payloadJson) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        return mockTokenRequest("header." + payload + ".signature");
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
        when(patRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.of(new Pat()));
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
        when(patRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.of(new Pat()));
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
        when(patRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.of(new Pat()));
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

    private String createSignedRunnerToken(String base64Secret, String workspaceId, String stepId, String jti) {
        byte[] secretBytes = io.jsonwebtoken.io.Decoders.BASE64URL.decode(base64Secret);
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretBytes);
        var builder = io.jsonwebtoken.Jwts.builder()
                .issuer("TerrakubeInternal")
                .subject("TerrakubeInternal (TOKEN)")
                .audience().add("TerrakubeInternal").and()
                .claim("workspaceId", workspaceId)
                .claim("organizationId", UUID.randomUUID().toString())
                .claim("jobId", UUID.randomUUID().toString())
                .issuedAt(java.util.Date.from(java.time.Instant.now()))
                .expiration(java.util.Date.from(java.time.Instant.now().plus(60, java.time.temporal.ChronoUnit.SECONDS)))
                .signWith(key);
        if (stepId != null) {
            builder.claim("stepId", stepId);
        }
        if (jti != null) {
            builder.id(jti);
        }
        return builder.compact();
    }

    @Test
    void resolve_internalRunnerToken_stepRunning_authenticatesSuccessfully() {
        byte[] secretBytes = org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(64).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.running);
        when(stepRepository.findById(stepUuid)).thenReturn(java.util.Optional.of(step));

        DexAuthenticationManagerResolver resolver = DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(secret)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .stepRepository(stepRepository)
                .build();

        String token = createSignedRunnerToken(secret, UUID.randomUUID().toString(), stepUuid.toString(), "job-step-jti");
        AuthenticationManager manager = resolver.resolve(mockTokenRequest(token));

        assertThat(manager).isNotNull();
        var authResult = manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token));
        assertThat(authResult).isNotNull();
        assertThat(authResult.isAuthenticated()).isTrue();
    }

    @Test
    void resolve_internalRunnerToken_stepCompleted_throwsAuthenticationException() {
        byte[] secretBytes = org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(64).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.completed);
        when(stepRepository.findById(stepUuid)).thenReturn(java.util.Optional.of(step));

        DexAuthenticationManagerResolver resolver = DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(secret)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .stepRepository(stepRepository)
                .build();

        String token = createSignedRunnerToken(secret, UUID.randomUUID().toString(), stepUuid.toString(), "job-step-jti");
        AuthenticationManager manager = resolver.resolve(mockTokenRequest(token));

        assertThat(manager).isNotNull();
        assertThatThrownBy(() -> manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token)))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void resolve_internalRunnerToken_stepFailed_throwsAuthenticationException() {
        byte[] secretBytes = org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(64).getBytes();
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        UUID stepUuid = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepUuid);
        step.setStatus(JobStatus.failed);
        when(stepRepository.findById(stepUuid)).thenReturn(java.util.Optional.of(step));

        DexAuthenticationManagerResolver resolver = DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(secret)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .stepRepository(stepRepository)
                .build();

        String token = createSignedRunnerToken(secret, UUID.randomUUID().toString(), stepUuid.toString(), "job-step-jti");
        AuthenticationManager manager = resolver.resolve(mockTokenRequest(token));

        assertThat(manager).isNotNull();
        assertThatThrownBy(() -> manager.authenticate(new org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken(token)))
                .isInstanceOf(AuthenticationException.class);
    }

    private DexAuthenticationManagerResolver patResolver(String secret) {
        return DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(secret)
                .internalJwtSecret(Base64.getUrlEncoder().encodeToString("internal-secret-internal-secret-x".getBytes()))
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build();
    }

    private String randomSecret() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                org.apache.commons.lang3.RandomStringUtils.secure().nextAlphanumeric(32).getBytes());
    }

    @Test
    void resolve_patToken_withoutJti_isRejected() {
        String secret = randomSecret();
        byte[] secretBytes = io.jsonwebtoken.io.Decoders.BASE64URL.decode(secret);
        String token = io.jsonwebtoken.Jwts.builder()
                .issuer("Terrakube")
                .subject("Test Subject")
                .claim("email", "test@terrakube.io")
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretBytes))
                .compact();

        assertThatThrownBy(() -> patResolver(secret).resolve(mockTokenRequest(token)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void resolve_patToken_withNonUuidJti_isRejected() {
        assertThatThrownBy(() -> patResolver(randomSecret()).resolve(
                tokenRequest("{\"iss\":\"Terrakube\",\"jti\":\"not-a-uuid\"}")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void resolve_patToken_withUnknownJti_isRejected() {
        when(patRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patResolver(randomSecret()).resolve(
                tokenRequest("{\"iss\":\"Terrakube\",\"jti\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void resolve_patToken_revoked_isRejected() {
        Pat pat = new Pat();
        pat.setDeleted(true);
        when(patRepository.findById(any(UUID.class))).thenReturn(Optional.of(pat));

        assertThatThrownBy(() -> patResolver(randomSecret()).resolve(
                tokenRequest("{\"iss\":\"Terrakube\",\"jti\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void resolve_teamToken_active_authenticates() {
        String secret = randomSecret();
        when(patRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(any(UUID.class))).thenReturn(Optional.of(new Group()));

        String token = createSignedToken("Terrakube", secret);
        var authResult = patResolver(secret).resolve(mockTokenRequest(token))
                .authenticate(new BearerTokenAuthenticationToken(token));

        assertThat(authResult.isAuthenticated()).isTrue();
    }

    @Test
    void resolve_teamToken_revoked_isRejected() {
        Group group = new Group();
        group.setDeleted(true);
        when(patRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(teamTokenRepository.findById(any(UUID.class))).thenReturn(Optional.of(group));

        assertThatThrownBy(() -> patResolver(randomSecret()).resolve(
                tokenRequest("{\"iss\":\"Terrakube\",\"jti\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(BadCredentialsException.class);
    }

    private Jwt dexJwt(List<String> audiences) {
        return Jwt.withTokenValue("dex-token")
                .header("alg", "RS256")
                .issuer("https://dummy-dex-issuer")
                .audience(audiences)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("alice")
                .claim("email", "alice@example.com")
                .build();
    }

    private AuthenticationManager dexManagerWithAudiences(List<String> accepted, JwtDecoder decoder,
                                                          MockedStatic<JwtDecoders> jwtDecoders) {
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());
        jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer")).thenReturn(decoder);
        return DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .dexAudiences(accepted)
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build()
                .resolve(dexTokenRequest());
    }

    @Test
    void resolve_dexToken_withAcceptedAudience_authenticates() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("dex-token")).thenReturn(dexJwt(List.of("other", "terrakube-app")));

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            AuthenticationManager manager = dexManagerWithAudiences(List.of("terrakube-app"), decoder, jwtDecoders);

            assertThat(manager.authenticate(new BearerTokenAuthenticationToken("dex-token")).isAuthenticated()).isTrue();
        }
    }

    @Test
    void resolve_dexToken_forAnotherClient_isRejectedWhenAudienceValidationIsOn() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("dex-token")).thenReturn(dexJwt(List.of("other-client")));

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            AuthenticationManager manager = dexManagerWithAudiences(List.of("terrakube-app"), decoder, jwtDecoders);

            assertThatThrownBy(() -> manager.authenticate(new BearerTokenAuthenticationToken("dex-token")))
                    .isInstanceOf(AuthenticationException.class);
        }
    }

    @Test
    void resolve_dexToken_forAnotherClient_isAcceptedWhenAudienceValidationIsOff() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("dex-token")).thenReturn(dexJwt(List.of("other-client")));

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            AuthenticationManager manager = dexManagerWithAudiences(List.of(), decoder, jwtDecoders);

            assertThat(manager.authenticate(new BearerTokenAuthenticationToken("dex-token")).isAuthenticated()).isTrue();
        }
    }
}
