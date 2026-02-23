package io.terrakube.api.rs.checks.template;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.template.Template;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageTemplate.RULE)
public class TeamManageTemplate extends OperationCheck<Template> {

    public static final String RULE = "team manage template";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Template template, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage template {}", template.getId());
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Team> teamList = template.getOrganization().getTeam();
        for (Team team : teamList) {
            if (isServiceAccount){
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManageTemplate(team) ){
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManageTemplate(team))
                    return true;
            }
        }
        return false;
    }
}
