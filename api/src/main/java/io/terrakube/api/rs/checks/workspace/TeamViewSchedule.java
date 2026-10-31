package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewSchedule.RULE)
public class TeamViewSchedule extends OperationCheck<Schedule> {

    public static final String RULE = "team view schedule";

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(Schedule schedule, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (schedule == null) {
            return false;
        }
        log.debug("team view schedule {}", schedule.getId());
        return workspaceCheckSupport.canViewWorkspace(schedule.getWorkspace(), requestScope);
    }
}

