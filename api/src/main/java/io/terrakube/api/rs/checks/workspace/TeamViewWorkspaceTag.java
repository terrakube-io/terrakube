package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewWorkspaceTag.RULE)
public class TeamViewWorkspaceTag extends OperationCheck<WorkspaceTag> {

    public static final String RULE = "team view workspace tag";

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(WorkspaceTag workspaceTag, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (workspaceTag == null) {
            return false;
        }
        log.debug("team view workspace tag {}", workspaceTag.getId());
        return workspaceCheckSupport.canViewWorkspace(workspaceTag.getWorkspace(), requestScope);
    }
}

