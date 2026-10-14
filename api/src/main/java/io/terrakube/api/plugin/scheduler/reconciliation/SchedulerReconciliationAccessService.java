package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gate for the scheduler reconciliation admin endpoint. Only backend service tokens (EXECUTOR_SERVICE)
 * or users whose groups include the configured instance owner are granted admin access.
 * Runner tokens (WORKSPACE_EXECUTOR) are strictly denied.
 */
@Service
public class SchedulerReconciliationAccessService {

    private final String instanceOwner;

    public SchedulerReconciliationAccessService(@Value("${io.terrakube.owner}") String instanceOwner) {
        this.instanceOwner = instanceOwner;
    }

    public boolean isAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        InternalTokenType tokenType = InternalTokenClassifier.classify(token.getTokenAttributes());
        if (tokenType == InternalTokenType.EXECUTOR_SERVICE) {
            return true;
        } else if (tokenType != InternalTokenType.NOT_INTERNAL) {
            return false;
        }
        Object groups = token.getTokenAttributes().get("groups");
        return groups instanceof List<?> list && list.contains(instanceOwner);
    }
}
