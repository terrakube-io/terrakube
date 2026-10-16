package io.terrakube.registry.configuration.authentication.dex;

import io.jsonwebtoken.Jwts;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.registry.service.search.CommonSearchService;
import io.terrakube.registry.service.module.ModuleAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunnerTokenRegistryTest {

    @TempDir
    Path dir;

    KeyPair keys;
    TerrakubeClient terrakubeClient = mock(TerrakubeClient.class);

    @BeforeEach
    void setup() throws Exception {
        keys = rsa();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String token(KeyPair signer, String audience, boolean hardened) {
        return Jwts.builder()
                .issuer(RunnerTokenDecoder.ISSUER)
                .audience().add(audience).and()
                .claim("organizationId", "org-id")
                .claim("workspaceId", "ws")
                .claim("jobId", "1")
                .claim("stepId", "s")
                .claim("hardened", hardened)
                .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .signWith(signer.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private RegistryAuthenticationManagerResolver resolver(boolean withKey) throws Exception {
        Path pem = dir.resolve("public.pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        return RegistryAuthenticationManagerResolver.builder()
                .issuerUri("https://dex")
                .patSecret("cGF0LXNlY3JldC1wYXQtc2VjcmV0LXBhdC1zZWNyZXQ=")
                .internalSecret("aW50ZXJuYWwtc2VjcmV0LWludGVybmFsLXNlY3JldA==")
                .terrakubeClient(terrakubeClient)
                .runnerPublicKey(withKey ? RunnerTokenDecoder.readPublicKey(pem.toString()) : null)
                .build();
    }

    private HttpServletRequest request(String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        return request;
    }

    @Test
    void runnerTokensAreRefusedWithoutTheRunnerKey() throws Exception {
        RegistryAuthenticationManagerResolver resolver = resolver(false);
        assertThrows(BadCredentialsException.class, () -> resolver.resolve(request(token(keys, RunnerTokenDecoder.AUDIENCE, true))));
        verifyNoInteractions(terrakubeClient);
    }

    @Test
    void runnerTokensAreVerifiedWithTheRunnerKey() throws Exception {
        RegistryAuthenticationManagerResolver resolver = resolver(true);
        String good = token(keys, RunnerTokenDecoder.AUDIENCE, true);
        AuthenticationManager manager = resolver.resolve(request(good));
        Authentication authentication = manager.authenticate(new BearerTokenAuthenticationToken(good));
        assertTrue(authentication.isAuthenticated());
        verifyNoInteractions(terrakubeClient);

        for (String bad : new String[]{token(rsa(), RunnerTokenDecoder.AUDIENCE, true),
                token(keys, "other", true), token(keys, RunnerTokenDecoder.AUDIENCE, false)}) {
            AuthenticationManager badManager = resolver.resolve(request(bad));
            assertThrows(AuthenticationException.class, () -> badManager.authenticate(new BearerTokenAuthenticationToken(bad)));
        }
    }

    @Test
    void runnerTokensOnlyReachModulesOfTheirOrganization() throws Exception {
        RegistryAuthenticationManagerResolver resolver = resolver(true);
        String good = token(keys, RunnerTokenDecoder.AUDIENCE, true);
        Authentication authentication = resolver.resolve(request(good)).authenticate(new BearerTokenAuthenticationToken(good));

        CommonSearchService search = mock(CommonSearchService.class);
        when(search.getOrganizationId("mine")).thenReturn("org-id");
        when(search.getOrganizationId("other")).thenReturn("other-id");
        ModuleAuthorizationService authorization = new ModuleAuthorizationService(search, "http://api",
                "aW50ZXJuYWwtc2VjcmV0LWludGVybmFsLXNlY3JldA==", "owners", "DEX");

        assertTrue(authorization.isAuthorized(authentication, "mine"));
        assertFalse(authorization.isAuthorized(authentication, "other"));
    }

    @Test
    void malformedKeyFilesAreRejected() throws Exception {
        Path pem = dir.resolve("bad.pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");
        assertThrows(Exception.class, () -> RunnerTokenDecoder.readPublicKey(pem.toString()));
    }
}
