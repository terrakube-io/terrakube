package io.terrakube.api.plugin.scheduler.reconciliation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gate for the scheduler reconciliation admin endpoint. Mirrors the Elide {@code isSuperService}
 * rule: an internal-issuer token, or a token whose groups include the configured instance owner.
 */
@Service
public class SchedulerReconciliationAccessService {

    private static final String INTERNAL_ISSUER = "TerrakubeInternal";

    private final String instanceOwner;

    public SchedulerReconciliationAccessService(@Value("${io.terrakube.owner}") String instanceOwner) {
        this.instanceOwner = instanceOwner;
    }

    public boolean isAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        if (INTERNAL_ISSUER.equals(token.getTokenAttributes().get("iss"))) {
            return true;
        }
        Object groups = token.getTokenAttributes().get("groups");
        return groups instanceof List<?> list && list.contains(instanceOwner);
    }
}
