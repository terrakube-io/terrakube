package io.terrakube.api.rs.checks.variable;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
import io.terrakube.api.rs.workspace.parameters.Variable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageVariable.RULE)
public class TeamManageVariable extends OperationCheck<Variable> {

    public static final String RULE = "team manage variable";

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(Variable variable, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (variable == null) {
            return false;
        }
        log.debug("team manage variable {}", variable.getId());
        return workspaceCheckSupport.canManageWorkspace(variable.getWorkspace(), requestScope);
    }
}

