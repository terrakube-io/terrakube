package io.terrakube.executor.service.workspace.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSecurityImplTest {

    private static final String SECRET = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("0123456789abcdef0123456789abcdef0123456789abcdef".getBytes());

    private final WorkspaceSecurityImpl subject = new WorkspaceSecurityImpl(null, "registry.example.com", "https://api.example.com", SECRET);

    @Test
    void jobTokenIsScopedToItsWorkspaceForAtMostADay() {
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(SECRET)))
                .build()
                .parseSignedClaims(subject.generateAccessToken("ws-1"))
                .getPayload();

        assertEquals("ws-1", claims.get("workspaceId"));
        assertTrue(claims.getExpiration().toInstant().isBefore(Instant.now().plus(Duration.ofDays(1)).plusSeconds(60)));
    }

    @Test
    void jobTokenWithoutWorkspaceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> subject.generateAccessToken((String) null));
        assertThrows(IllegalArgumentException.class, () -> subject.generateAccessToken(" "));
    }
}
