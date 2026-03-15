package io.terrakube.api.rs.checks.membership;

import com.yahoo.elide.core.security.User;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.team.Team;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;

@Service
@Slf4j
public class MembershipService {

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    public boolean checkMembership(User user, List<Team> teamList) {
        boolean isFederatedAccount = authenticatedUser.isFederatedAccount(user);
        for (Team team : teamList) {
            if (authenticatedUser.isServiceAccount(user)) {
                String applicationName = authenticatedUser.getApplication(user);
                if (groupService.isServiceMember(user, team.getName())) {
                    log.debug("application {} is member of {}", applicationName, team.getName());
                    return true;
                }
            } else if (isFederatedAccount) {
                boolean isFederatedMember = groupService.isFederatedMember(user, team.getName());
                if (isFederatedMember)
                    return true;
            } else {
                String userName = authenticatedUser.getEmail(user);
                if (groupService.isMember(user, team.getName())) {
                    log.debug("user {} is member of {}", userName, team.getName());
                    return true;
                }
            }
        }
        return false;
    }

    public boolean checkLimitedMembership(User user, List<Access> teamList) {
        for (Access team : teamList) {
            if (authenticatedUser.isServiceAccount(user)) {
                String applicationServiceName = authenticatedUser.getApplication(user);
                if (groupService.isServiceMember(user, team.getName())) {
                    log.debug("application service {} is member of {}", applicationServiceName, team.getName());
                    return true;
                }
            } else {
                String userMail = authenticatedUser.getEmail(user);
                if (groupService.isMember(user, team.getName())) {
                    log.debug("user {} is limited member of {}", userMail, team.getName());
                    return true;
                }
            }
        }
        return false;
    }

}
