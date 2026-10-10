package io.terrakube.api.rs.checks.vcs;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.checks.user.IsRegistryService;
import io.terrakube.api.rs.vcs.Vcs;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@SecurityCheck(ReadAccessToken.RULE)
public class ReadAccessToken extends OperationCheck<Vcs> {
    public static final String RULE = "read access token";

    private final IsRegistryService isRegistryService = new IsRegistryService();

    @Override
    public boolean ok(Vcs vcs, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (requestScope.getUser() != null && isRegistryService.ok(requestScope.getUser())) {
            return true;
        }
        return false;
    }
}
