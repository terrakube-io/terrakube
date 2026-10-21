package io.terrakube.api.rs.hooks.step;

import com.yahoo.elide.core.exceptions.HttpStatusException;

public class StepDeliveryStateException extends HttpStatusException {
    public StepDeliveryStateException(int status, String message) {
        super(status, message);
    }
}
