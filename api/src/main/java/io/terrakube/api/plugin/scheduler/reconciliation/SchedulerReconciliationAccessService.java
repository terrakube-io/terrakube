package io.terrakube.api.plugin.scheduler.reconciliation;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Gate for the scheduler reconciliation admin endpoint. Only backend service tokens (EXECUTOR_SERVICE)
 * or users whose groups include the configured instance owner are granted admin access.
 * Runner tokens (WORKSPACE_EXECUTOR) are strictly denied.
 */
@Service
public class SchedulerReconciliationAccessService {

    private final String instanceOwner;
    private final GroupService groupService;

    public SchedulerReconciliationAccessService(@Value("${io.terrakube.owner}") String instanceOwner,
            GroupService groupService) {
        this.instanceOwner = instanceOwner;
        this.groupService = groupService;
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
        // Effective groups: a federated token acts as its Federated credentials, not as its groups claim
        return groupService.getEffectiveGroups(new User(token)).contains(instanceOwner);
    }
}
