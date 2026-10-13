package io.terrakube.api.plugin.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.terrakube.api.plugin.security.audit.dex.DexAuditorAwareImpl;

class WebhookAuditContextTest {

    private final DexAuditorAwareImpl auditor = new DexAuditorAwareImpl();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        assertThat(WebhookAuditContext.currentActor()).isEmpty();
    }

    @Test
    void scopedActorDoesNotChangeAuthenticationAndRestoresAuthenticatedAuditor() {
        Jwt jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none")
                .claim("email", "operator@example.com").build();
        var authentication = new JwtAuthenticationToken(jwt, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        WebhookAuditContext.withActor("pr-author", () -> {
            assertThat(auditor.getCurrentAuditor()).contains("pr-author");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
            return null;
        });

        assertThat(auditor.getCurrentAuditor()).contains("operator@example.com");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }

    @Test
    void exceptionRestoresPreviousActorAndClearsOutermostScope() {
        WebhookAuditContext.withActor("outer-author", () -> {
            assertThatThrownBy(() -> WebhookAuditContext.withActor("inner-author", () -> {
                assertThat(auditor.getCurrentAuditor()).contains("inner-author");
                throw new IllegalStateException("save failed");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(auditor.getCurrentAuditor()).contains("outer-author");
            return null;
        });
        assertThat(auditor.getCurrentAuditor()).contains("Internal");
    }

    @Test
    void absentActorUsesExistingAuthenticationAndAnonymousBehavior() {
        var anonymous = new AnonymousAuthenticationToken("test", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);
        assertThat(WebhookAuditContext.withActor(null, auditor::getCurrentAuditor)).isEmpty();
        assertThat(WebhookAuditContext.withActor("", auditor::getCurrentAuditor)).isEmpty();
        assertThat(WebhookAuditContext.withActor(" ", auditor::getCurrentAuditor)).isEmpty();
        assertThat(WebhookAuditContext.withActor("pr-author", auditor::getCurrentAuditor)).contains("pr-author");
        assertThat(auditor.getCurrentAuditor()).isEmpty();
    }

    @Test
    void concurrentWorkersKeepSeparateActorsAndClearThemOnReuse() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> WebhookAuditContext.withActor("first-author", () -> {
                await(barrier);
                return auditor.getCurrentAuditor();
            }));
            var second = workers.submit(() -> WebhookAuditContext.withActor("second-author", () -> {
                await(barrier);
                return auditor.getCurrentAuditor();
            }));
            assertThat(first.get(10, TimeUnit.SECONDS)).contains("first-author");
            assertThat(second.get(10, TimeUnit.SECONDS)).contains("second-author");
            var afterFirst = workers.submit(() -> { await(barrier); return auditor.getCurrentAuditor(); });
            var afterSecond = workers.submit(() -> { await(barrier); return auditor.getCurrentAuditor(); });
            assertThat(afterFirst.get(10, TimeUnit.SECONDS)).contains("Internal");
            assertThat(afterSecond.get(10, TimeUnit.SECONDS)).contains("Internal");
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
