package io.terrakube.executor.configuration.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
        assertThrows(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class, () ->
                manager.authenticate(new BearerTokenAuthenticationToken(tokenSignedWithDifferentKey))
        );
    }
}
