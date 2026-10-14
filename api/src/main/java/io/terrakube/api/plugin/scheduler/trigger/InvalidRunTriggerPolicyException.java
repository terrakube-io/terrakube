package io.terrakube.api.plugin.scheduler.trigger;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/** A dependency policy cannot be evaluated consistently or safely. */
public class InvalidRunTriggerPolicyException extends HttpStatusException {
    public InvalidRunTriggerPolicyException(String message) {
        super(HttpStatus.SC_BAD_REQUEST, message);
    }
}
