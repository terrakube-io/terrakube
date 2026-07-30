package io.terrakube.api.rs.hooks.organization;

import org.apache.hc.core5.http.HttpStatus;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.manage.OrganizationManageService;
import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.rs.Organization;

import java.util.List;
import java.util.Optional;

@AllArgsConstructor
@Slf4j
public class OrganizationManageHook implements LifeCycleHook<Organization> {

    OrganizationManageService organizationManageService;

    SoftDeleteService softDeleteService;

    OrganizationRepository organizationRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase transactionPhase, Organization organization, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.info("OrganizationManageHook {}", organization.getId());
            switch (operation) {
                case CREATE:
                    switch (transactionPhase) {
                        case PRECOMMIT:
                            List<Organization> matches = organizationRepository.findAllByName(organization.getName());
                            boolean conflict = matches.stream()
                                    .anyMatch(match -> !match.getId().equals(organization.getId()));
                            if (conflict) {
                                throw new OrganizationManagementException(HttpStatus.SC_CONFLICT,
                                        "An organization named " + organization.getName() + " already exists.");
                            }
                            break;
                        case POSTCOMMIT:
                            try {
                                organizationManageService.postCreationSetup(organization);
                            } catch (Exception e) {
                                log.error("postCreationSetup failed for organization {}", organization.getId(), e);
                                throw e;
                            }
                            break;
                        default:
                            break;
                    }
                    break;
                case UPDATE:
                    if(organization.isDisabled()){
                        softDeleteService.disableOrganization(organization);
                    }
                    break;
                default:
                    log.info("Not supported");
                    break;
            }

    }
}
