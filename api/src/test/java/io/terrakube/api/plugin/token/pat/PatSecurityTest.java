package io.terrakube.api.plugin.token.pat;

import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.rs.token.pat.Pat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatSecurityTest {

    @Mock
    private PatRepository patRepository;

    @InjectMocks
    private PatService patService;

    private static final String OWNER_EMAIL = "alice@example.com";
    private static final String ATTACKER_EMAIL = "bob@example.com";
    private static final String SUPERUSER_ROLE = "SUPER_ADMIN";
    private static final String SUPERUSER_EMAIL = "admin@example.com";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(patService, "instanceOwner", SUPERUSER_ROLE);
    }

    private JwtAuthenticationToken createJwtAuth(String email, List<String> groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        claims.put("sub", email);
        if (groups != null) {
            claims.put("groups", groups);
        }
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "none"), claims);
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    void testOwnerCanRevokeOwnToken() {
        UUID patId = UUID.randomUUID();
        Pat pat = new Pat();
        pat.setId(patId);
        pat.setCreatedBy(OWNER_EMAIL);
        pat.setDeleted(false);

        when(patRepository.findById(patId)).thenReturn(Optional.of(pat));
        when(patRepository.save(any(Pat.class))).thenReturn(pat);

        JwtAuthenticationToken ownerAuth = createJwtAuth(OWNER_EMAIL, List.of("DEV_TEAM"));

        boolean result = patService.deleteToken(patId.toString(), ownerAuth);

        assertTrue(result);
        assertTrue(pat.isDeleted());
        verify(patRepository).save(pat);
    }

    @Test
    void testNonOwnerCannotRevokeOtherUserToken() {
        UUID patId = UUID.randomUUID();
        Pat pat = new Pat();
        pat.setId(patId);
        pat.setCreatedBy(OWNER_EMAIL);
        pat.setDeleted(false);

        when(patRepository.findById(patId)).thenReturn(Optional.of(pat));

        JwtAuthenticationToken attackerAuth = createJwtAuth(ATTACKER_EMAIL, List.of("DEV_TEAM"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                patService.deleteToken(patId.toString(), attackerAuth)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertFalse(pat.isDeleted());
        verify(patRepository, never()).save(any());
    }

    @Test
    void testInstanceOwnerCanRevokeOtherUserToken() {
        UUID patId = UUID.randomUUID();
        Pat pat = new Pat();
        pat.setId(patId);
        pat.setCreatedBy(OWNER_EMAIL);
        pat.setDeleted(false);

        when(patRepository.findById(patId)).thenReturn(Optional.of(pat));
        when(patRepository.save(any(Pat.class))).thenReturn(pat);

        // Caller belongs to instanceOwner group SUPER_ADMIN
        JwtAuthenticationToken superuserAuth = createJwtAuth(SUPERUSER_EMAIL, List.of(SUPERUSER_ROLE, "OTHER_TEAM"));

        boolean result = patService.deleteToken(patId.toString(), superuserAuth);

        assertTrue(result);
        assertTrue(pat.isDeleted());
        verify(patRepository).save(pat);
    }

    @Test
    void testNonExistentTokenReturnsNotFound() {
        UUID patId = UUID.randomUUID();
        when(patRepository.findById(patId)).thenReturn(Optional.empty());

        JwtAuthenticationToken ownerAuth = createJwtAuth(OWNER_EMAIL, List.of("DEV_TEAM"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                patService.deleteToken(patId.toString(), ownerAuth)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(patRepository, never()).save(any());
    }

    @Test
    void testAlreadyDeletedTokenReturnsNotFound() {
        UUID patId = UUID.randomUUID();
        Pat pat = new Pat();
        pat.setId(patId);
        pat.setCreatedBy(OWNER_EMAIL);
        pat.setDeleted(true);

        when(patRepository.findById(patId)).thenReturn(Optional.of(pat));

        JwtAuthenticationToken ownerAuth = createJwtAuth(OWNER_EMAIL, List.of("DEV_TEAM"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                patService.deleteToken(patId.toString(), ownerAuth)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(patRepository, never()).save(any());
    }

    @Test
    void testInvalidUuidFormatReturnsNotFound() {
        JwtAuthenticationToken ownerAuth = createJwtAuth(OWNER_EMAIL, List.of("DEV_TEAM"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                patService.deleteToken("invalid-uuid-string", ownerAuth)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(patRepository, never()).save(any());
    }
}
