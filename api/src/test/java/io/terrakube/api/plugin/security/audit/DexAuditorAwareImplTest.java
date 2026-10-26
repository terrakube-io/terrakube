package io.terrakube.api.plugin.security.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.terrakube.api.plugin.security.audit.dex.DexAuditorAwareImpl;

class DexAuditorAwareImplTest {
    private final DexAuditorAwareImpl auditor = new DexAuditorAwareImpl();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("synthetic-test-token").header("alg", "RS256")
                .claims(values -> values.putAll(claims)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void keepsEmailAttributionForExistingUsers() {
        authenticate(Map.of("email", "operator@example.com", "sub", "user-123"));
        assertThat(auditor.getCurrentAuditor()).contains("operator@example.com");
    }

    @Test
    void attributesWorkloadTokensWithoutEmailToTheirSubject() {
        String subject = "repo:example/infrastructure:environment:production";
        authenticate(Map.of("iss", "https://token.actions.githubusercontent.com", "sub", subject));
        assertThat(auditor.getCurrentAuditor()).contains(subject);
    }

    @Test
    void blankAndNonStringEmailsDoNotHideTheSubject() {
        for (Object email : List.of("", "   ", 123)) {
            authenticate(Map.of("email", email, "sub", "workload-123"));
            assertThat(auditor.getCurrentAuditor()).contains("workload-123");
        }
    }

    @Test
    void subjectUsesTheExistingAuditDisplaySanitizationAndLengthLimit() {
        authenticate(Map.of("sub", "  workload\n\u202E" + "x".repeat(200)));
        assertThat(auditor.getCurrentAuditor()).contains("workload" + "x".repeat(120));
    }

    @Test
    void scopedVerifiedActorsStillTakePrecedence() {
        authenticate(Map.of("sub", "workload-123"));
        assertThat(AuditActorContext.withActor("webhook-author", auditor::getCurrentAuditor))
                .contains("webhook-author");
        assertThat(auditor.getCurrentAuditor()).contains("workload-123");
    }

    @Test
    void retainsUnknownForTokensWithNoUsableIdentity() {
        authenticate(Map.of("iss", "https://issuer.example"));
        assertThat(auditor.getCurrentAuditor()).contains("Unknown");
    }
}
