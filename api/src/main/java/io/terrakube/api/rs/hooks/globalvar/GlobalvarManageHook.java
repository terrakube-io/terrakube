package io.terrakube.api.rs.hooks.globalvar;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.rs.globalvar.Globalvar;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpStatus;

import java.util.Optional;

@AllArgsConstructor
@Slf4j
public class GlobalvarManageHook implements LifeCycleHook<Globalvar> {

    GlobalVarRepository globalVarRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Globalvar globalvar, RequestScope requestScope,
            Optional<ChangeSpec> optional) {
        log.info("Globalvar mutation hook for globalvar {} in org {}", globalvar.getKey(),
                globalvar.getOrganization() != null ? globalvar.getOrganization().getName() : "unknown");
        switch (operation) {
            case CREATE:
            case UPDATE:
                if (transactionPhase == LifeCycleHookBinding.TransactionPhase.PRECOMMIT) {
                    validateNoDuplicateGlobalvar(globalvar);
                }
                break;
            default:
                break;
        }
    }

    private void validateNoDuplicateGlobalvar(Globalvar globalvar) {
        if (globalvar.getOrganization() != null && globalvar.getKey() != null) {
            Optional<Globalvar> match = globalVarRepository.findByOrganizationAndKey(
                    globalvar.getOrganization(), globalvar.getKey());
            boolean conflict = match.isPresent() && !match.get().getId().equals(globalvar.getId());
            if (conflict) {
                throw new GlobalvarManagementException(HttpStatus.SC_CONFLICT,
                        "A global variable with key '" + globalvar.getKey() + "' already exists in this organization.");
            }
        }
    }
}
