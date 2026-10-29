package io.terrakube.api.rs.checks.variable;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.workspace.parameters.Variable;

import java.util.Optional;

@SecurityCheck(UpdateVariableSecret.RULE)
public class UpdateVariableSecret extends OperationCheck<Variable> {

    public static final String RULE = "user update secret";

    @Override
    public boolean ok(Variable variable, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (optional.isPresent() && Boolean.TRUE.equals(optional.get().getOriginal()) && Boolean.FALSE.equals(optional.get().getModified())) {
            return false;
        }
        return true;
    }
}

