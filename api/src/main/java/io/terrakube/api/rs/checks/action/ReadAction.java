package io.terrakube.api.rs.checks.action;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.User;
import com.yahoo.elide.core.security.checks.UserCheck;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SecurityCheck(ReadAction.RULE)
public class ReadAction extends UserCheck {

    public static final String RULE = "read action";

    @Override
    public boolean ok(User user) {
        return user != null && user.getPrincipal() != null;
    }
}

