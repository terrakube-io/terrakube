package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * Mirrors Workspace's own three-tier update permission ("team manage workspace OR team project limited manage
 * workspace OR team limited manage workspace") for its tag bindings. Without it Elide only guards adding a
 * binding through the workspace relationship, leaving any organization member free to change or delete the
 * value of an existing one.
 */
@Slf4j
@SecurityCheck(TeamManageWorkspaceTag.RULE)
public class TeamManageWorkspaceTag extends OperationCheck<WorkspaceTag> {

    public static final String RULE = "team manage workspace tag";

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(WorkspaceTag workspaceTag, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (workspaceTag == null || workspaceTag.getWorkspace() == null) {
            return false;
        }
        log.debug("team manage workspace tag {}", workspaceTag.getId());
        return workspaceCheckSupport.canManageWorkspace(workspaceTag.getWorkspace(), requestScope);
    }
}
