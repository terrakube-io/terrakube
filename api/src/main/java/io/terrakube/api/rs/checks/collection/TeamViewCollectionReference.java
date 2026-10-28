package io.terrakube.api.rs.checks.collection;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewCollectionReference.RULE)
public class TeamViewCollectionReference extends OperationCheck<Reference> {

    public static final String RULE = "team view collection reference";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(Reference reference, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (reference == null) {
            return false;
        }
        log.debug("team view collection reference {}", reference.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        if (!isStructurallyValid(reference)) {
            return false;
        }
        if (reference.getCollection() != null && reference.getCollection().getOrganization() != null) {
            List<Team> teamList = reference.getCollection().getOrganization().getTeam();
            if (membershipService.checkMembership(requestScope.getUser(), teamList)) {
                return true;
            }
        }
        if (reference.getWorkspace() != null) {
            return workspaceCheckSupport.canViewWorkspace(reference.getWorkspace(), requestScope);
        }
        return false;
    }

    private boolean isStructurallyValid(Reference reference) {
        if (reference.getCollection() == null && reference.getWorkspace() == null) {
            return false;
        }
        if (reference.getCollection() != null && reference.getWorkspace() != null) {
            if (reference.getCollection().getOrganization() == null
                    || reference.getWorkspace().getOrganization() == null
                    || reference.getCollection().getOrganization().getId() == null
                    || !reference.getCollection().getOrganization().getId()
                            .equals(reference.getWorkspace().getOrganization().getId())) {
                return false;
            }
        }
        return true;
    }
}

