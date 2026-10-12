package io.terrakube.api.rs.checks.user;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.User;
import com.yahoo.elide.core.security.checks.UserCheck;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Slf4j
@SecurityCheck(IsExecutorService.RULE)
public class IsExecutorService extends UserCheck {

    public static final String RULE = "user is an executor service";

    @Override
    public boolean ok(User user) {
        if (user != null && user.getPrincipal() instanceof JwtAuthenticationToken jwt) {
            return InternalTokenClassifier.classify(jwt.getTokenAttributes()) == InternalTokenType.EXECUTOR_SERVICE;
        }
        return false;
    }
}
