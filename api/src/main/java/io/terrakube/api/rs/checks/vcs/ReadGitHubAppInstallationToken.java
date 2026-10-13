package io.terrakube.api.rs.checks.vcs;

import java.util.Optional;

import io.terrakube.api.rs.checks.user.IsRegistryService;
import io.terrakube.api.rs.vcs.GitHubAppToken;
import org.springframework.beans.factory.annotation.Autowired;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;

@SecurityCheck(ReadGitHubAppInstallationToken.RULE)
public class ReadGitHubAppInstallationToken extends OperationCheck<GitHubAppToken> {
    public static final String RULE = "read github app installation token";

    private final IsRegistryService isRegistryService = new IsRegistryService();

    @Override
    public boolean ok(GitHubAppToken object, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (requestScope.getUser() != null && isRegistryService.ok(requestScope.getUser())) {
            return true;
        }
        return false;
    }
}
