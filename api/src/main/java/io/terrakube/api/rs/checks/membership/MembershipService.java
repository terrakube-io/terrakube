package io.terrakube.api.rs.checks.membership;

import com.yahoo.elide.core.security.User;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.team.Team;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
@Slf4j
public class MembershipService {

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    private boolean isUserMemberOfGroup(User user, String groupName) {
        if (authenticatedUser.isServiceAccount(user)) {
            return groupService.isServiceMember(user, groupName);
        } else if (authenticatedUser.isFederatedAccount(user)) {
            return groupService.isFederatedMember(user, groupName);
        } else {
            return groupService.isMember(user, groupName);
        }
    }

    public boolean checkMembership(User user, List<Team> teamList) {
        if (teamList == null) {
            return false;
        }
        for (Team team : teamList) {
            if (isUserMemberOfGroup(user, team.getName())) {
                log.debug("user/service is member of team {}", team.getName());
                return true;
            }
        }
        return false;
    }

    public boolean checkLimitedMembership(User user, List<Access> teamList) {
        if (teamList == null) {
            return false;
        }
        for (Access team : teamList) {
            if (isUserMemberOfGroup(user, team.getName())) {
                log.debug("user/service is limited member of {}", team.getName());
                return true;
            }
        }
        return false;
    }

    public boolean checkProjectMembership(User user, List<ProjectAccess> accessList, Function<ProjectAccess, Boolean> permissionCheck) {
        if (accessList == null) {
            return false;
        }
        for (ProjectAccess access : accessList) {
            if (isUserMemberOfGroup(user, access.getName()) && permissionCheck.apply(access)) {
                log.debug("user/service has project-level access via {}", access.getName());
                return true;
            }
        }
        return false;
    }

    /** True when the user (or service account) belongs to one of the teams and that team passes the permission check. */
    public boolean checkTeamPermission(User user, List<Team> teamList, Predicate<Team> permissionCheck) {
        if (teamList == null) {
            return false;
        }
        for (Team team : teamList) {
            if (isUserMemberOfGroup(user, team.getName()) && permissionCheck.test(team)) {
                return true;
            }
        }
        return false;
    }

    /** True when the user (or service account) belongs to one of the workspace access groups and that access passes the permission check. */
    public boolean checkAccessPermission(User user, List<Access> accessList, Predicate<Access> permissionCheck) {
        if (accessList == null) {
            return false;
        }
        for (Access access : accessList) {
            if (isUserMemberOfGroup(user, access.getName()) && permissionCheck.test(access)) {
                return true;
            }
        }
        return false;
    }
}
