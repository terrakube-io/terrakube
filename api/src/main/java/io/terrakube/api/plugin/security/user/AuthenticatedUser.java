package io.terrakube.api.plugin.security.user;

import com.yahoo.elide.core.security.User;

public interface AuthenticatedUser {

    String getEmail(User user);

    String getApplication(User user);

    boolean isServiceAccount(User user);

    boolean isFederatedAccount(User user);

    boolean isServiceAccountInternal(User user);

    boolean isSuperUser(User user);
}
