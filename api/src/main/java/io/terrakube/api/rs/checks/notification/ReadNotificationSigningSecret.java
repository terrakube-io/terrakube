package io.terrakube.api.rs.checks.notification;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.rs.notification.NotificationConfiguration;

import java.util.Optional;

@Slf4j
@SecurityCheck(ReadNotificationSigningSecret.RULE)
public class ReadNotificationSigningSecret extends OperationCheck<NotificationConfiguration> {
    public static final String RULE = "read notification signing secret";

    @Override
    public boolean ok(NotificationConfiguration configuration, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("user view notification signing secret {}", configuration.getId());
        return false;
    }
}
