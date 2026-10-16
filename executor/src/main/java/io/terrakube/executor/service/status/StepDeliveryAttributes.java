package io.terrakube.executor.service.status;

import io.terrakube.client.model.organization.job.step.StepAttributes;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StepDeliveryAttributes extends StepAttributes {
    private String deliveryState;
    private Long deliveryRevision;
    private Long deliveryTimestamp;
}
