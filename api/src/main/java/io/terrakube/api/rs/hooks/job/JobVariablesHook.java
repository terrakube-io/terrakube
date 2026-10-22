package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.exceptions.BadRequestException;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.execution.JobVariablesService;
import io.terrakube.api.rs.job.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JobVariablesHook implements LifeCycleHook<Job> {
    private final JobVariablesService variables;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Job job, RequestScope scope,
                        Optional<ChangeSpec> changes) {
        if (operation == Operation.CREATE && phase == TransactionPhase.PREFLUSH) {
            try {
                job.setEncryptedVariables(variables.encrypt(job.getVariables()));
                job.setVariables(null);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(e.getMessage());
            }
        }
    }
}
