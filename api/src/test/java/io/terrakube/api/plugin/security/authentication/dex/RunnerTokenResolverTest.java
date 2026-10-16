package io.terrakube.api.plugin.security.authentication.dex;

import io.jsonwebtoken.Jwts;
import io.terrakube.api.plugin.hardened.RunnerTokenService;
import io.terrakube.api.plugin.security.token.RunnerToken;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RunnerTokenResolverTest {

    FederatedRepository federatedRepository = mock(FederatedRepository.class);
    RunnerTokenService runnerTokenService = mock(RunnerTokenService.class);
    KeyPair keys;

    @BeforeEach
    void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        when(runnerTokenService.getPublicKey()).thenReturn((RSAPublicKey) keys.getPublic());
    }

    private DexAuthenticationManagerResolver resolver() {
        return DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dex")
                .patJwtSecret("cGF0LXNlY3JldC1wYXQtc2VjcmV0LXBhdC1zZWNyZXQ=")
                .internalJwtSecret("aW50ZXJuYWwtc2VjcmV0LWludGVybmFsLXNlY3JldA==")
                .patRepository(mock(PatRepository.class))
                .teamTokenRepository(mock(TeamTokenRepository.class))
                .federatedRepository(federatedRepository)
                .runnerTokenService(runnerTokenService)
                .build();
    }

    private String token(KeyPair signer) {
        return Jwts.builder()
                .issuer(RunnerToken.ISSUER)
                .audience().add(RunnerToken.AUDIENCE).and()
                .claim("organizationId", "org")
                .claim("workspaceId", "ws")
                .claim("jobId", "1")
                .claim("stepId", "s")
                .claim("flow", "terraformPlan")
                .claim("hardened", true)
                .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .signWith(signer.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private HttpServletRequest request(String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);
        return request;
    }

    @Test
    void runnerTokensAreRefusedOutsideHardenedMode() {
        when(runnerTokenService.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> resolver().resolve(request(token(keys)))).isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(federatedRepository);
    }

    @Test
    void runnerTokensAreVerifiedAgainstTheRunnerKeyBeforeAnyFederatedLookup() throws Exception {
        when(runnerTokenService.isEnabled()).thenReturn(true);
        String token = token(keys);
        AuthenticationManager manager = resolver().resolve(request(token));

        Authentication authentication = manager.authenticate(new BearerTokenAuthenticationToken(token));
        assertThat(authentication).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(((JwtAuthenticationToken) authentication).getTokenAttributes()).containsEntry("workspaceId", "ws");
        verifyNoInteractions(federatedRepository);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String forged = token(generator.generateKeyPair());
        AuthenticationManager forgedManager = resolver().resolve(request(forged));
        assertThatThrownBy(() -> forgedManager.authenticate(new BearerTokenAuthenticationToken(forged)))
                .isInstanceOf(AuthenticationException.class);
    }
}
