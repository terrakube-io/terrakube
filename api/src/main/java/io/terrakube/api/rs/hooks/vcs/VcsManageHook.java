package io.terrakube.api.rs.hooks.vcs;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.rs.vcs.Vcs;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;

@Slf4j
public class VcsManageHook implements LifeCycleHook<Vcs> {

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
                        LifeCycleHookBinding.TransactionPhase transactionPhase, Vcs vcs, RequestScope requestScope,
                        Optional<ChangeSpec> optional) {
        if (Objects.requireNonNull(operation) == LifeCycleHookBinding.Operation.CREATE) {
            if (Objects.requireNonNull(transactionPhase) == LifeCycleHookBinding.TransactionPhase.POSTCOMMIT) {
                log.info("Creating new vcs {} {}", vcs.getId(), vcs.getName());
            }
        }
    }
}
