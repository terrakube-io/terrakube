package io.terrakube.api;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.plugin.security.groups.dex.DexGroupServiceImpl;
import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.repository.ProjectAccessRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.federated.claim.FederatedClaim;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DexGroupServiceTests {

    private static final String ISSUER = "https://token.actions.githubusercontent.com";
    private static final String AUDIENCE = "terrakube";
    private static final String GROUP = "ci-trigger";

    @Test
    void federatedTokenWithoutGroupsClaimIsMember() {
        DexGroupServiceImpl groupService = groupServiceWith(federated(GROUP, Map.of("repository", "acme/infra")));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/infra"));

        assertTrue(groupService.isServiceMember(user, GROUP));
    }

    @Test
    void federatedTokenWithDecodedAudienceCollectionIsMember() {
        DexGroupServiceImpl groupService = groupServiceWith(federated(GROUP, Map.of("repository", "acme/infra")));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", List.of("another-service", AUDIENCE),
                "repository", "acme/infra"));

        assertTrue(groupService.isServiceMember(user, GROUP));
    }

    @Test
    void federatedTokenWithClaimMismatchIsNotMember() {
        DexGroupServiceImpl groupService = groupServiceWith(federated(GROUP, Map.of("repository", "acme/infra")));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "someone-else/infra"));

        assertFalse(groupService.isServiceMember(user, GROUP));
    }

    @Test
    void tokenWithGroupsClaimIsStillMember() {
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());
        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, null, null, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", "https://dex.example.com",
                "aud", "terrakube",
                "groups", new java.util.ArrayList<>(List.of("TERRAKUBE_ADMIN"))));

        assertTrue(groupService.isServiceMember(user, "TERRAKUBE_ADMIN"));
        assertFalse(groupService.isServiceMember(user, "other-group"));
    }

    @Test
    void isMemberWithLimitedAccessV2_federatedToken_hasWorkspaceAccess() {
        Federated federated = federated(GROUP, Map.of("repository", "acme/infra"));
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of(federated));

        AccessRepository accessRepository = mock(AccessRepository.class);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        when(accessRepository.findAllByWorkspaceOrganizationIdAndNameIn(eq(organization.getId()), eq(List.of(GROUP))))
                .thenReturn(Optional.of(List.of(mock(Access.class))));

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, accessRepository, null, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/infra"));

        assertTrue(groupService.isMemberWithLimitedAccessV2(user, organization));
    }

    @Test
    void isMemberWithLimitedAccessV2_federatedToken_noWorkspaceAccess() {
        Federated federated = federated(GROUP, Map.of("repository", "acme/infra"));
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of(federated));

        AccessRepository accessRepository = mock(AccessRepository.class);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        when(accessRepository.findAllByWorkspaceOrganizationIdAndNameIn(eq(organization.getId()), eq(List.of(GROUP))))
                .thenReturn(Optional.of(List.of()));

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, accessRepository, null, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/infra"));

        assertFalse(groupService.isMemberWithLimitedAccessV2(user, organization));
    }

    @Test
    void isMemberWithLimitedAccessV2_tokenWithoutGroups_andNotFederated_doesNotThrowNpe() {
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());
        AccessRepository accessRepository = mock(AccessRepository.class);

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, accessRepository, null, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", "https://dex.example.com",
                "aud", "terrakube"));

        assertDoesNotThrow(() -> {
            boolean result = groupService.isMemberWithLimitedAccessV2(user, organization);
            assertFalse(result);
        });
        verifyNoInteractions(accessRepository);
    }

    @Test
    void isMemberWithProjectAccess_federatedToken_hasProjectAccess() {
        Federated federated = federated(GROUP, Map.of("repository", "acme/infra"));
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of(federated));

        ProjectAccessRepository projectAccessRepository = mock(ProjectAccessRepository.class);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        when(projectAccessRepository.findAllByProjectOrganizationIdAndNameIn(eq(organization.getId()), eq(List.of(GROUP))))
                .thenReturn(Optional.of(List.of(mock(ProjectAccess.class))));

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, null, projectAccessRepository, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/infra"));

        assertTrue(groupService.isMemberWithProjectAccess(user, organization));
    }

    @Test
    void isMemberWithProjectAccess_federatedToken_noProjectAccess() {
        Federated federated = federated(GROUP, Map.of("repository", "acme/infra"));
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of(federated));

        ProjectAccessRepository projectAccessRepository = mock(ProjectAccessRepository.class);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        when(projectAccessRepository.findAllByProjectOrganizationIdAndNameIn(eq(organization.getId()), eq(List.of(GROUP))))
                .thenReturn(Optional.of(List.of()));

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, null, projectAccessRepository, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/infra"));

        assertFalse(groupService.isMemberWithProjectAccess(user, organization));
    }

    @Test
    void isMemberWithProjectAccess_tokenWithoutGroups_andNotFederated_doesNotThrowNpe() {
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());
        ProjectAccessRepository projectAccessRepository = mock(ProjectAccessRepository.class);

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, null, projectAccessRepository, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", "https://dex.example.com",
                "aud", "terrakube"));

        assertDoesNotThrow(() -> {
            boolean result = groupService.isMemberWithProjectAccess(user, organization);
            assertFalse(result);
        });
        verifyNoInteractions(projectAccessRepository);
    }

    @Test
    void isFederatedMember_multipleCredentials_matchesAny() {
        Federated cred1 = federated("TEAM_A", Map.of("repository", "acme/infra"));
        Federated cred2 = federated("TEAM_B", Map.of("repository", "acme/infra"));

        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of(cred1, cred2));

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, null, null, new FederatedLookupService(federatedRepository));

        User user = userWith(Map.of(
                "iss", ISSUER,
                "aud", AUDIENCE,
                "repository", "acme/infra"));

        assertTrue(groupService.isFederatedMember(user, "TEAM_A"));
        assertTrue(groupService.isFederatedMember(user, "TEAM_B"));
        assertFalse(groupService.isFederatedMember(user, "TEAM_C"));
    }

    @Test
    void tokenWithNonArrayListGroups_doesNotThrowClassCastException() {
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(List.of());

        DexGroupServiceImpl groupService =
                new DexGroupServiceImpl(null, null, null, new FederatedLookupService(federatedRepository));

        // Use List.of which returns an immutable list that is NOT java.util.ArrayList
        User user = userWith(Map.of(
                "iss", "https://dex.example.com",
                "aud", "terrakube",
                "groups", List.of("TEAM_CUSTOM")));

        assertDoesNotThrow(() -> {
            assertTrue(groupService.isMember(user, "TEAM_CUSTOM"));
            assertTrue(groupService.isServiceMember(user, "TEAM_CUSTOM"));
            assertFalse(groupService.isMember(user, "OTHER"));
        });
    }

    private DexGroupServiceImpl groupServiceWith(Federated federated) {
        FederatedRepository federatedRepository = mock(FederatedRepository.class);
        when(federatedRepository.findAllByIssuerUrlAndAudience(ISSUER, AUDIENCE)).thenReturn(List.of(federated));
        return new DexGroupServiceImpl(null, null, null, new FederatedLookupService(federatedRepository));
    }

    private Federated federated(String name, Map<String, String> claims) {
        Federated federated = new Federated();
        federated.setName(name);
        federated.setIssuerUrl(ISSUER);
        federated.setAudience(AUDIENCE);
        federated.setClaims(claims.entrySet().stream().map(entry -> {
            FederatedClaim claim = new FederatedClaim();
            claim.setClaimKey(entry.getKey());
            claim.setClaimValue(entry.getValue());
            return claim;
        }).toList());
        return federated;
    }

    private User userWith(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claims(jwtClaims -> jwtClaims.putAll(claims))
                .build();
        return new User(new JwtAuthenticationToken(jwt));
    }
}
