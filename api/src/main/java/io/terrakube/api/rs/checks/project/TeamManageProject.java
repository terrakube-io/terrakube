package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageProject.RULE)
public class TeamManageProject extends OperationCheck<Project> {

    public static final String RULE = "team manage project";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Override
    public boolean ok(Project project, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage project {}", project.getId());
        List<Team> teamList = project.getOrganization().getTeam();
        for (Team team : teamList) {
            if (authenticatedUser.isServiceAccount(requestScope.getUser())){
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && team.isManageWorkspace() ){
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && team.isManageWorkspace())
                    return true;
            }
        }
        return false;
    }
}



