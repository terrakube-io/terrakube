package io.terrakube.api.rs.checks.announcement;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class AnnouncementCheckSupport {

    private AnnouncementCheckSupport() {
    }

    static boolean isInternalOrUnauthenticated(User user) {
        if (user == null || user.getPrincipal() == null) {
            return true;
        }
        if (user.getPrincipal() instanceof JwtAuthenticationToken jwt) {
            return InternalTokenClassifier.classify(jwt.getTokenAttributes()) != InternalTokenType.NOT_INTERNAL;
        }
        return false;
    }
}
