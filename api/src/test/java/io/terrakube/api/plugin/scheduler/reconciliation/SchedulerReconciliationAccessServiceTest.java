package io.terrakube.api.plugin.scheduler.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerReconciliationAccessServiceTest {

    private SchedulerReconciliationAccessService accessService;
    private final String instanceOwner = "terrakube-admins";

    @BeforeEach
    void setUp() {
        accessService = new SchedulerReconciliationAccessService(instanceOwner);
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
    void testIsAdmin_NullOrNonJwt_ReturnsFalse() {
        assertThat(accessService.isAdmin(null)).isFalse();
    }
}
