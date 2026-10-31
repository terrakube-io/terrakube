package io.terrakube.api.rs.checks.collection;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageCollectionReference.RULE)
public class TeamManageCollectionReference extends OperationCheck<Reference> {

    public static final String RULE = "team manage collection reference";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private RbacService rbacService;

    @Override
    public boolean ok(Reference reference, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (reference == null) {
            return false;
        }
        log.debug("team manage collection reference {}", reference.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        if (!isStructurallyValid(reference)) {
            return false;
        }
        if (reference.getCollection().getOrganization() == null || reference.getCollection().getOrganization().getTeam() == null) {
            return false;
        }
        List<Team> teamList = reference.getCollection().getOrganization().getTeam();
        return membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageCollection);
    }

    private boolean isStructurallyValid(Reference reference) {
        if (reference.getCollection() == null) {
            return false;
        }
        if (reference.getWorkspace() != null) {
            if (reference.getCollection().getOrganization() == null
                    || reference.getWorkspace().getOrganization() == null
                    || reference.getCollection().getOrganization().getId() == null
                    || !reference.getCollection().getOrganization().getId()
                            .equals(reference.getWorkspace().getOrganization().getId())) {
                log.warn("Rejecting collection reference: collection and workspace belong to different organizations");
                return false;
            }
        }
        return true;
    }
}

