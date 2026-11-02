package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.plugin.security.groups.dex.DexGroupServiceImpl;
import io.terrakube.api.rs.federated.Federated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerReconciliationAccessServiceTest {

    private SchedulerReconciliationAccessService accessService;
    private FederatedLookupService federatedLookupService;
    private final String instanceOwner = "terrakube-admins";

    @BeforeEach
    void setUp() {
        federatedLookupService = mock(FederatedLookupService.class);
        accessService = new SchedulerReconciliationAccessService(instanceOwner,
                new DexGroupServiceImpl(null, null, null, federatedLookupService));
    }

    private JwtAuthenticationToken mockJwt(Map<String, Object> claims) {
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getTokenAttributes()).thenReturn(claims);
        return token;
    }

    @Test
    void testIsAdmin_ExecutorService_ReturnsTrue() {
        JwtAuthenticationToken token = mockJwt(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));

        assertThat(accessService.isAdmin(token)).isTrue();
    }

    @Test
    void testIsAdmin_WorkspaceExecutor_ReturnsFalse() {
        JwtAuthenticationToken token = mockJwt(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)",
                "workspaceId", UUID.randomUUID().toString()
        ));

        assertThat(accessService.isAdmin(token)).isFalse();
    }

    @Test
    void testIsAdmin_UnknownInternal_ReturnsFalse() {
        JwtAuthenticationToken token = mockJwt(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));

        assertThat(accessService.isAdmin(token)).isFalse();
    }

    @Test
    void testIsAdmin_InstanceOwnerGroup_ReturnsTrue() {
        JwtAuthenticationToken token = mockJwt(Map.of(
                "iss", "https://dex.example.com",
                "groups", List.of(instanceOwner, "dev-team")
        ));

        assertThat(accessService.isAdmin(token)).isTrue();
    }

    @Test
    void testIsAdmin_OtherGroup_ReturnsFalse() {
        JwtAuthenticationToken token = mockJwt(Map.of(
                "iss", "https://dex.example.com",
                "groups", List.of("dev-team")
        ));

        assertThat(accessService.isAdmin(token)).isFalse();
    }

    @Test
    void testIsAdmin_FederatedTokenWithInstanceOwnerGroupsClaim_ReturnsFalse() {
        // The external identity provider decides the groups claim; the token acts as its credential only
        Federated federated = new Federated();
        federated.setName("ci-team");
        when(federatedLookupService.findAllAuthorized(any())).thenReturn(List.of(federated));
        JwtAuthenticationToken token = mockJwt(Map.of(
                "iss", "https://token.actions.githubusercontent.com",
                "groups", List.of(instanceOwner)
        ));

        assertThat(accessService.isAdmin(token)).isFalse();
    }

    @Test
    void testIsAdmin_NullOrNonJwt_ReturnsFalse() {
        assertThat(accessService.isAdmin(null)).isFalse();
    }
}
