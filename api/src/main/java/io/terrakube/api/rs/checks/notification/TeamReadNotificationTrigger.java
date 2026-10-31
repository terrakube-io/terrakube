package io.terrakube.api.rs.checks.notification;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.notification.NotificationTrigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamReadNotificationTrigger.RULE)
public class TeamReadNotificationTrigger extends OperationCheck<NotificationTrigger> {

    public static final String RULE = "team read notification trigger";

    @Autowired
    private NotificationCheckSupport notificationCheckSupport;

    @Override
    public boolean ok(NotificationTrigger notificationTrigger, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (notificationTrigger == null || notificationTrigger.getConfiguration() == null) {
            return false;
        }
        log.debug("team read notification trigger {}", notificationTrigger.getId());
        return notificationCheckSupport.canReadConfiguration(notificationTrigger.getConfiguration(), requestScope);
    }
}

