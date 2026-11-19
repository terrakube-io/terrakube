package io.terrakube.executor.configuration.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurityImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ExecutorManagerResolverTest {

    @Mock
    private HttpServletRequest request;

    private String generateSecret(int byteLength) {
        byte[] randomBytes = RandomStringUtils.secure().nextAlphanumeric(byteLength).getBytes();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String createSignedToken(String base64Secret) {
        byte[] secretBytes = Decoders.BASE64URL.decode(base64Secret);
        SecretKey key = Keys.hmacShaKeyFor(secretBytes);
        return Jwts.builder()
                .issuer("TERRAKUBE_INTERNAL")
                .subject("Terrakube Internal (Token)")
                .audience().add("TERRAKUBE_INTERNAL").and()
                .id(UUID.randomUUID().toString())
                .claim("email", "internal@terrakube.io")
                .claim("email_verified", true)
                .claim("name", "Terrakube Api")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();
    }

    @Test
    void resolve_32ByteSecret_supportsHS256() {
        String secret = generateSecret(32);
        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();

        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);
        assertTrue(manager instanceof ProviderManager);

        String token = createSignedToken(secret);
        var authResult = manager.authenticate(new BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
        assertTrue(authResult.getAuthorities().stream()
                .anyMatch(a -> ExecutorManagerResolver.ROLE_API_DISPATCHER.equals(a.getAuthority())));
    }

    @Test
    void resolve_48ByteSecret_supportsHS384() {
        String secret = generateSecret(48);
        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();

        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);
        assertTrue(manager instanceof ProviderManager);

        String token = createSignedToken(secret);
        var authResult = manager.authenticate(new BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
        assertTrue(authResult.getAuthorities().stream()
                .anyMatch(a -> ExecutorManagerResolver.ROLE_API_DISPATCHER.equals(a.getAuthority())));
    }

    @Test
    void resolve_64ByteSecret_supportsHS512() {
        String secret = generateSecret(64);
        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();

        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);
        assertTrue(manager instanceof ProviderManager);

        String token = createSignedToken(secret);
        var authResult = manager.authenticate(new BearerTokenAuthenticationToken(token));
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());
        assertTrue(authResult.getAuthorities().stream()
                .anyMatch(a -> ExecutorManagerResolver.ROLE_API_DISPATCHER.equals(a.getAuthority())));
    }

    @Test
    void resolve_tokenWithWrongSecret_failsAuthentication() {
        String secret = generateSecret(64);
        String anotherSecret = generateSecret(64);
        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();

        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        String tokenSignedWithDifferentKey = createSignedToken(anotherSecret);
        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(tokenSignedWithDifferentKey))
        );
    }

    @Test
    void resolve_runnerTokenFromWorkspaceSecurity_isRejected() {
        String secret = generateSecret(64);
        WorkspaceSecurityImpl workspaceSecurity = new WorkspaceSecurityImpl(
                new RestClientProperties(),
                "registry.example.com",
                "https://api.example.com",
                secret
        );
        String runnerToken = workspaceSecurity.generateAccessToken(60, "org-1", "ws-1", "job-1", "step-1");

        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();
        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(runnerToken))
        );
    }

    @Test
    void resolve_executorServiceTokenFromWorkspaceSecurity_isRejected() {
        String secret = generateSecret(64);
        WorkspaceSecurityImpl workspaceSecurity = new WorkspaceSecurityImpl(
                new RestClientProperties(),
                "registry.example.com",
                "https://api.example.com",
                secret
        );
        String executorServiceToken = workspaceSecurity.generateAccessToken(1);

        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();
        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(executorServiceToken))
        );
    }

    @Test
    void resolve_registryServiceToken_isRejected() {
        String secret = generateSecret(64);
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret));
        String registryToken = Jwts.builder()
                .issuer("TerrakubeInternal")
                .subject("TerrakubeInternal (REGISTRY)")
                .audience().add("TerrakubeInternal").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();

        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();
        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(registryToken))
        );
    }

    @Test
    void resolve_apiIssuerWithWorkspaceIdClaim_isRejected() {
        String secret = generateSecret(64);
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret));
        String tokenWithWorkspaceId = Jwts.builder()
                .issuer("TERRAKUBE_INTERNAL")
                .subject("Terrakube Internal (Token)")
                .audience().add("TERRAKUBE_INTERNAL").and()
                .claim("workspaceId", "ws-1")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();

        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();
        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(tokenWithWorkspaceId))
        );
    }

    @Test
    void resolve_expiredApiDispatchToken_isRejected() {
        String secret = generateSecret(64);
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret));
        String expiredToken = Jwts.builder()
                .issuer("TERRAKUBE_INTERNAL")
                .subject("Terrakube Internal (Token)")
                .audience().add("TERRAKUBE_INTERNAL").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now().minus(120, ChronoUnit.SECONDS)))
                .expiration(Date.from(Instant.now().minus(60, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();

        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();
        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(expiredToken))
        );
    }

    @Test
    void resolve_apiTokenWithoutExpectedAudience_isRejected() {
        String secret = generateSecret(64);
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret));
        String tokenWithoutAudience = Jwts.builder()
                .issuer("TERRAKUBE_INTERNAL")
                .subject("Terrakube Internal (Token)")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();

        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();
        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(tokenWithoutAudience))
        );
    }

    @Test
    void resolve_apiTokenWithWrongAudience_isRejected() {
        String secret = generateSecret(64);
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secret));
        String tokenWithWrongAudience = Jwts.builder()
                .issuer("TERRAKUBE_INTERNAL")
                .subject("Terrakube Internal (Token)")
                .audience().add("TerrakubeInternal").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();

        ExecutorManagerResolver resolver = ExecutorManagerResolver.builder()
                .internalJwtSecret(secret)
                .build();
        AuthenticationManager manager = resolver.resolve(request);
        assertNotNull(manager);

        assertThrows(InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(tokenWithWrongAudience))
        );
    }

    @Test
    void apiDispatchAuthenticationConverter_grantsAuthorityOnlyForValidDispatchClaims() {
        Jwt validJwt = Jwt.withTokenValue("t")
                .header("alg", "HS512")
                .claim("iss", ExecutorManagerResolver.EXPECTED_ISSUER)
                .subject(ExecutorManagerResolver.EXPECTED_SUBJECT)
                .audience(java.util.List.of(ExecutorManagerResolver.EXPECTED_AUDIENCE))
                .build();
        Jwt runnerJwt = Jwt.withTokenValue("t")
                .header("alg", "HS512")
                .claim("iss", "TerrakubeInternal")
                .subject("TerrakubeInternal (EXECUTOR)")
                .audience(java.util.List.of("TerrakubeInternal"))
                .claim("workspaceId", "ws-1")
                .build();

        var converter = ExecutorManagerResolver.apiDispatchAuthenticationConverter();
        assertTrue(converter.convert(validJwt).getAuthorities().stream()
                .anyMatch(a -> ExecutorManagerResolver.ROLE_API_DISPATCHER.equals(a.getAuthority())));
        assertFalse(converter.convert(runnerJwt).getAuthorities().stream()
                .anyMatch(a -> ExecutorManagerResolver.ROLE_API_DISPATCHER.equals(a.getAuthority())));
    }
}
