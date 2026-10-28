package io.terrakube.api.rs.checks.collection;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageCollectionItem.RULE)
public class TeamManageCollectionItem extends OperationCheck<Item> {

    public static final String RULE = "team manage collection item";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private RbacService rbacService;

    @Override
    public boolean ok(Item item, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (item == null || item.getCollection() == null || item.getCollection().getOrganization() == null) {
            return false;
        }
        log.debug("team manage collection item {}", item.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = item.getCollection().getOrganization().getTeam();
        return membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageCollection);
    }
}

