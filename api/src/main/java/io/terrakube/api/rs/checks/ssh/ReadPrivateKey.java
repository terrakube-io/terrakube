package io.terrakube.api.rs.checks.ssh;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.checks.user.IsRegistryService;
import io.terrakube.api.rs.ssh.Ssh;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@SecurityCheck(ReadPrivateKey.RULE)
public class ReadPrivateKey extends OperationCheck<Ssh> {
    public static final String RULE = "read private key";
    private final IsRegistryService isRegistryService = new IsRegistryService();

    @Override
    public boolean ok(Ssh ssh, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (requestScope.getUser() != null && isRegistryService.ok(requestScope.getUser())) {
            return true;
        }
        return false;
    }
}
