package io.terrakube.api.plugin.token.team;

import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.repository.TeamTokenRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.token.group.Group;
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
class TeamTokenSecurityTest {

    @Mock
    private TeamTokenRepository teamTokenRepository;

    @Mock
    private FederatedLookupService federatedLookupService;

    @InjectMocks
    private TeamTokenService teamTokenService;

    private static final String SUPERUSER_ROLE = "SUPER_ADMIN";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(teamTokenService, "instanceOwner", SUPERUSER_ROLE);
    }

    private JwtAuthenticationToken createJwtAuth(List<String> groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", "alice@example.com");
        claims.put("sub", "alice@example.com");
        claims.put("groups", groups);
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "none"), claims);
        return new JwtAuthenticationToken(jwt);
    }

    private Group teamToken(UUID id, String group) {
        Group token = new Group();
        token.setId(id);
        token.setGroup(group);
        token.setDeleted(false);
        when(teamTokenRepository.findById(id)).thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void groupMemberCanDeleteTeamToken() {
        UUID id = UUID.randomUUID();
        Group token = teamToken(id, "DEV_TEAM");
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of());

        assertTrue(teamTokenService.deleteToken(id.toString(), createJwtAuth(List.of("DEV_TEAM"))));
        assertTrue(token.isDeleted());
        verify(teamTokenRepository).save(token);
    }

    @Test
    void nonMemberCannotDeleteTeamToken() {
        UUID id = UUID.randomUUID();
        Group token = teamToken(id, "DEV_TEAM");
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                teamTokenService.deleteToken(id.toString(), createJwtAuth(List.of("OTHER_TEAM"))));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertFalse(token.isDeleted());
        verify(teamTokenRepository, never()).save(any());
    }

    @Test
    void instanceOwnerCanDeleteAnyTeamToken() {
        UUID id = UUID.randomUUID();
        Group token = teamToken(id, "DEV_TEAM");
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of());

        assertTrue(teamTokenService.deleteToken(id.toString(), createJwtAuth(List.of(SUPERUSER_ROLE))));
        assertTrue(token.isDeleted());
    }

    @Test
    void instanceOwnerByEmailCanDeleteAnyTeamToken() {
        ReflectionTestUtils.setField(teamTokenService, "instanceOwner", "owner@example.com");
        UUID id = UUID.randomUUID();
        Group token = teamToken(id, "DEV_TEAM");
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of());

        Map<String, Object> claims = new HashMap<>();
        claims.put("email", "owner@example.com");
        claims.put("sub", "owner@example.com");
        claims.put("groups", List.of("SOME_OTHER_GROUP"));
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "none"), claims);

        assertTrue(teamTokenService.deleteToken(id.toString(), new JwtAuthenticationToken(jwt)));
        assertTrue(token.isDeleted());
    }

    @Test
    void federatedTokenWithOwnerEmailCannotDeleteTeamToken() {
        ReflectionTestUtils.setField(teamTokenService, "instanceOwner", "owner@example.com");
        UUID id = UUID.randomUUID();
        Group token = teamToken(id, "DEV_TEAM");
        Federated federated = new Federated();
        federated.setName("CI_TEAM");
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of(federated));

        Map<String, Object> claims = new HashMap<>();
        claims.put("email", "owner@example.com");
        claims.put("sub", "repo:org/repo");
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "none"), claims);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                teamTokenService.deleteToken(id.toString(), new JwtAuthenticationToken(jwt)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertFalse(token.isDeleted());
    }

    @Test
    void unknownOrInvalidTokenIdIsNotDeleted() {
        UUID id = UUID.randomUUID();
        when(teamTokenRepository.findById(id)).thenReturn(Optional.empty());

        assertFalse(teamTokenService.deleteToken(id.toString(), createJwtAuth(List.of("DEV_TEAM"))));
        assertFalse(teamTokenService.deleteToken("not-a-uuid", createJwtAuth(List.of("DEV_TEAM"))));
        verify(teamTokenRepository, never()).save(any());
    }

    @Test
    void nonMemberCannotCreateTeamToken() {
        assertEquals("", teamTokenService.createTeamToken("OPS_TEAM", 1, 0, 0, "token", createJwtAuth(List.of("DEV_TEAM"))));
        verify(teamTokenRepository, never()).save(any());
    }
}
