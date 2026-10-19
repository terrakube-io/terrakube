package io.terrakube.api.rs.hooks.step;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.rs.job.step.DeliveryState;
import io.terrakube.api.rs.job.step.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
public class StepDeliveryStateHook implements LifeCycleHook<Step> {

    @Override
    public void execute(Operation operation, TransactionPhase phase, Step step, RequestScope requestScope,
            Optional<ChangeSpec> changes) {
        if (operation == Operation.UPDATE || operation == Operation.CREATE) {
            if (changes != null && changes.isPresent()) {
                Object modified = changes.get().getModified();
                if (isIllegalExternalState(modified)) {
                    log.warn("Attempt to set illegal deliveryState {} externally on step {}", modified, step != null ? step.getId() : "null");
                    throw new StepDeliveryStateException(400, "deliveryState cannot be set externally to " + modified);
                }
            } else if (step != null && isIllegalExternalState(step.getDeliveryState())) {
                log.warn("Attempt to set illegal deliveryState {} externally on step {}", step.getDeliveryState(), step.getId());
                throw new StepDeliveryStateException(400, "deliveryState cannot be set externally to " + step.getDeliveryState());
            }
        }
    }

    private boolean isIllegalExternalState(Object state) {
        if (state == null) {
            return false;
        }
        if (state instanceof DeliveryState deliveryState) {
            return deliveryState == DeliveryState.RECOVERED || deliveryState == DeliveryState.EXPIRED;
        }
        String str = String.valueOf(state);
        return DeliveryState.RECOVERED.name().equalsIgnoreCase(str) || DeliveryState.EXPIRED.name().equalsIgnoreCase(str);
    }
}
